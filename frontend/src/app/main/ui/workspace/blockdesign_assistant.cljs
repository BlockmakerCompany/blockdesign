;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.main.ui.workspace.blockdesign-assistant
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.render-wasm.api :as wasm.api]
   [app.util.http :as http]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private max-context-shapes 240)

(defn- serialize-shape
  [shape]
  (cond-> {:id (str (:id shape))
           :parentId (some-> (:parent-id shape) str)
           :frameId (some-> (:frame-id shape) str)
           :type (some-> (:type shape) name)
           :name (:name shape)
           :x (:x shape)
           :y (:y shape)
           :width (:width shape)
           :height (:height shape)
           :rotation (:rotation shape)
           :opacity (:opacity shape)
           :hidden (:hidden shape)
           :blocked (:blocked shape)
           :children (mapv str (or (:shapes shape) []))
           :fills (mapv #(select-keys % [:fill-color :fill-opacity :fill-color-gradient]) (or (:fills shape) []))
           :strokes (mapv #(select-keys % [:stroke-color :stroke-opacity :stroke-width :stroke-style]) (or (:strokes shape) []))}
    (= :text (:type shape))
    (assoc :text (try
                   (some-> (:content shape) txt/content->text)
                   (catch :default error
                     (.warn js/console "Could not serialize text shape for assistant context" (:id shape) error)
                     nil)))))

(defn- context-shapes
  [scope selected objects]
  (let [ids (if (= scope :page)
              (->> objects keys (remove uuid/zero?))
              (mapcat (fn collect-ids [id]
                        (cons id (mapcat collect-ids (get-in objects [id :shapes]))))
                      selected))]
    (->> ids
         distinct
         (keep #(when (map? (get objects %)) (get objects %)))
         (take max-context-shapes)
         (mapv serialize-shape))))

(defn- sort-operations
  [operations]
  (let [creates (filter #(= "create" (:action %)) operations)
        others (remove #(= "create" (:action %)) operations)
        sorted-creates (sort-by (fn [op] (- (* (or (:width op) 0) (or (:height op) 0)))) creates)]
    (concat sorted-creates others)))

(defn- operation-id
  [operation]
  (some-> (:id operation) uuid/parse*))

(defn- text-content
  [text]
  {:type "root"
   :children
   [{:type "paragraph-set"
     :children
     [{:type "paragraph"
       :children [(merge (txt/get-default-text-attrs) {:text (or text "")})]}]}]})

(defn- update-shape
  [shape operation]
  (let [fill (:fill operation)
        stroke (:stroke operation)]
    (cond-> shape
      (:name operation) (assoc :name (:name operation))
      (:x operation) (assoc :x (:x operation))
      (:y operation) (assoc :y (:y operation))
      (:width operation) (assoc :width (:width operation))
      (:height operation) (assoc :height (:height operation))
      (:rotation operation) (assoc :rotation (:rotation operation))
      (:opacity operation) (assoc :opacity (:opacity operation))
      (and (= :text (:type shape)) (:text operation)) (assoc :content (text-content (:text operation)))
      fill (assoc :fills [{:fill-color fill :fill-opacity 1}])
      stroke (assoc :strokes [{:stroke-color stroke :stroke-width (or (:strokeWidth operation) 1) :stroke-style "solid" :stroke-opacity 1}]))))

(defn- apply-operation!
  [operation objects]
  (let [action (keyword (:action operation))
        id (operation-id operation)]
    (case action
      :update
      (if-let [shape (get objects id)]
        (do (st/emit! (dwsh/update-shapes [id] #(update-shape % operation)))
            true)
        false)

      :remove
      (do (st/emit! (dwsh/delete-shapes #{id}))
          true)

      :create
      (let [requested-type (keyword (:type operation))
            type (case requested-type
                   :rectangle :rect
                   :ellipse :circle
                   :board :frame
                   :text :text
                   :rect)
            shape {:id (or id (uuid/next))
                   :type type
                   :name (or (:name operation) (tr "blockdesign.assistant.new-layer" "Nueva capa"))
                   :x (or (:x operation) 0)
                   :y (or (:y operation) 0)
                   :width (or (:width operation) 100)
                   :height (or (:height operation) 100)
                   :rotation 0
                   :opacity 1}
            shape (cond-> shape
                    (:fill operation)
                    (assoc :fills [{:fill-color (:fill operation) :fill-opacity 1}])

                    (:stroke operation)
                    (assoc :strokes [{:stroke-color (:stroke operation)
                                      :stroke-width (or (:strokeWidth operation) 1)
                                      :stroke-style "solid"
                                      :stroke-opacity 1}])

                    (= type :text)
                    (assoc :content (text-content (:text operation))))]
        (st/emit! (dwsh/create-and-add-shape type (:x shape) (:y shape) shape
                                             (when (= type :text) {:skip-edition? true})))
        true)

      false)))

(defn- download-design-md!
  [content]
  (let [blob (js/Blob. #js [content] #js {:type "text/markdown;charset=utf-8"})
        url (.createObjectURL js/URL blob)
        anchor (.createElement js/document "a")]
    (set! (.-href anchor) url)
    (set! (.-download anchor) "DESIGN.md")
    (.click anchor)
    (.revokeObjectURL js/URL url)))

(defn- request-assistant!
  [payload profile on-success on-error]
  (let [request (http/send! {:method :post
                             :uri "/assistant-api/blockdesign-v2/assistant"
                             :response-type :json
                             :headers {"content-type" "application/json"
                                       "x-user-id" (some-> (:id profile) str)
                                       "x-user-email" (or (:email profile) "")}
                             :body (.stringify js/JSON (clj->js payload))})]
    (->> request
         (rx/subs!
          (fn [{:keys [status body]}]
            (let [data (js->clj body :keywordize-keys true)]
              (if (<= 200 status 299)
                (on-success data)
                (on-error (or (:error data) (tr "blockdesign.assistant.request-error" "Error"))))))
          (fn [error]
            (on-error (or (ex-message error) (tr "blockdesign.assistant.network-error" "Error de red"))))))))

(defn- blob-url->base64
  [blob-url on-success on-error]
  (let [xhr (js/XMLHttpRequest.)]
    (set! (.-onload xhr)
          (fn []
            (let [reader (js/FileReader.)]
              (set! (.-onloadend reader)
                    (fn []
                      (on-success (.. reader -result))))
              (.readAsDataURL reader (.-response xhr)))))
    (set! (.-onerror xhr) #(on-error "Error reading blob"))
    (.open xhr "GET" blob-url)
    (set! (.-responseType xhr) "blob")
    (.send xhr)))

(mf/defc assistant-panel*
  []
  (let [profile (mf/deref refs/profile)
        page (mf/deref refs/workspace-page)
        objects (mf/deref refs/workspace-page-objects)
        selected (mf/deref refs/selected-shapes)

        scope* (mf/use-state :selection)
        prompt* (mf/use-state "")
        messages* (mf/use-state [{:kind :assistant :text (tr "blockdesign.assistant.welcome" "Bienvenido al asistente nativo.")}])
        busy* (mf/use-state false)

        jira-query* (mf/use-state "")
        jira-results* (mf/use-state [])
        selected-jira* (mf/use-state nil)

        use-design-md* (mf/use-state false)
        current-design-md* (mf/use-state "")
        show-save-modal* (mf/use-state false)
        save-project-name* (mf/use-state "")
        save-description* (mf/use-state "")
        save-to-library* (mf/use-state true)
        define-hierarchy* (mf/use-state true)

        scope (deref scope*)
        prompt (deref prompt*)
        messages (deref messages*)
        busy? (deref busy*)

        jira-query (deref jira-query*)
        jira-results (deref jira-results*)
        selected-jira (deref selected-jira*)

        use-design-md (deref use-design-md*)
        current-design-md (deref current-design-md*)
        show-save-modal (deref show-save-modal*)
        save-project-name (deref save-project-name*)
        save-description (deref save-description*)
        save-to-library (deref save-to-library*)
        define-hierarchy (deref define-hierarchy*)

        selection (mf/with-memo [scope selected objects]
                    (context-shapes scope selected objects))

        close-panel
        (mf/use-fn #(st/emit! (dw/remove-layout-flag :blockdesign-assistant)))

        add-message!
        (mf/use-fn
         (fn [kind text]
           (swap! messages* conj {:kind kind :text text})))

        context
        {:scope (name scope)
         :pageName (or (:name page) (tr "blockdesign.assistant.page" "Página"))
         :selection selection}

        search-jira!
        (mf/use-fn
         (mf/deps profile)
         (fn [q]
           (when-not (str/blank? q)
             (let [request (http/send! {:method :get
                                        :uri (str "/assistant-api/jira/issues/search?q=" (js/encodeURIComponent q))
                                        :response-type :json
                                        :headers {"x-user-id" (some-> (:id profile) str)
                                                  "x-user-email" (or (:email profile) "")}})]
               (->> request
                    (rx/subs!
                     (fn [{:keys [status body]}]
                       (let [data (js->clj body :keywordize-keys true)]
                         (when (<= 200 status 299)
                           (reset! jira-results* (:tasks data)))))
                     (fn [error]
                       (println "Jira search error:" error))))))))

        on-jira-search-change
        (mf/use-fn
         (fn [event]
           (let [v (.. event -target -value)]
             (reset! jira-query* v)
             (if (> (count v) 2)
               (search-jira! v)
               (reset! jira-results* [])))))

        share-to-jira!
        (mf/use-fn
         (mf/deps profile selected-jira busy?)
         (fn []
           (when (and selected-jira (not busy?))
             (reset! busy* true)
             (-> (wasm.api/capture-canvas-snapshot-url)
                 (.then
                  (fn [blob-url]
                    (if blob-url
                      (blob-url->base64
                       blob-url
                       (fn [base64-data]
                         (let [comment-msg (.prompt js/window "Escribí un comentario opcional para Jira:" "")
                               payload {:imageBase64 base64-data
                                        :previewUrl (.-href js/window.location)
                                        :message (if (str/blank? comment-msg) "Captura de pantalla de la UI adjunta desde BlockDesign." comment-msg)}
                               request (http/send! {:method :post
                                                    :uri (str "/assistant-api/jira/issues/" (:id selected-jira) "/comment")
                                                    :response-type :json
                                                    :headers {"content-type" "application/json"
                                                              "x-user-id" (some-> (:id profile) str)
                                                              "x-user-email" (or (:email profile) "")}
                                                    :body (.stringify js/JSON (clj->js payload))})]
                           (->> request
                                (rx/subs!
                                 (fn [{:keys [status]}]
                                   (if (<= 200 status 299)
                                     (add-message! :assistant (str "Captura publicada en Jira para el ticket: " (:id selected-jira)))
                                     (add-message! :error "No se pudo publicar la captura en Jira."))
                                   (reset! busy* false))
                                 (fn [error]
                                   (add-message! :error (ex-message error))
                                   (reset! busy* false))))))
                       (fn [error]
                         (add-message! :error error)
                         (reset! busy* false)))
                      (do
                        (add-message! :error "No se pudo realizar la captura del canvas.")
                        (reset! busy* false)))))))))

        on-send
        (mf/use-fn
         (mf/deps prompt context profile objects busy? selected-jira use-design-md current-design-md)
         (fn [event]
           (.preventDefault event)
           (when (and (not busy?) (not (str/blank? prompt)))
             (let [submitted prompt]
               (reset! prompt* "")
               (reset! busy* true)
               (add-message! :user submitted)
               (request-assistant!
                (cond-> {:action "edit" :prompt submitted :context context}
                  selected-jira (assoc :jiraContext {:id (:id selected-jira) :title (:title selected-jira)})
                  (and use-design-md (not (str/blank? current-design-md))) (assoc :designMd current-design-md))
                profile
                (fn [data]
                  (let [sorted-ops (sort-operations (:operations data))
                        changed (count (filter true? (map #(apply-operation! % objects) sorted-ops)))]
                    (add-message! :assistant
                                  (or (:summary data)
                                      (tr "blockdesign.assistant.applied" (str "Se aplicaron " changed " cambios") changed)))
                    (reset! busy* false)))
                (fn [message]
                  (add-message! :error message)
                  (reset! busy* false)))))))

        trigger-generate-design-md!
        (mf/use-fn
         (mf/deps context profile save-project-name save-description save-to-library define-hierarchy busy?)
         (fn []
           (when-not busy?
             (reset! show-save-modal* false)
             (reset! busy* true)
             (request-assistant!
              {:action "design-md"
               :context (assoc context :defineHierarchy define-hierarchy)
               :saveToLibrary save-to-library
               :projectName save-project-name
               :description save-description}
              profile
              (fn [data]
                (let [content (or (:content data) "")]
                  (download-design-md! content)
                  (reset! current-design-md* content)
                  (reset! use-design-md* true)
                  (add-message! :assistant "DESIGN.md generado con éxito y descargado.")
                  (reset! busy* false)))
              (fn [message]
                (add-message! :error message)
                (reset! busy* false))))))

        on-design-md
        (mf/use-fn
         (fn []
           (reset! save-project-name* (or (:name page) "Nuevo Proyecto"))
           (reset! show-save-modal* true)))]

    [:section {:class (stl/css :assistant-panel)}
     [:header {:class (stl/css :assistant-header)}
      [:div
       [:h2 (tr "blockdesign.assistant.title" "Asistente UI")]
       [:p (tr "blockdesign.assistant.subtitle" "Agente nativo de BlockDesign 2.0")]]
      [:button {:type "button"
                :aria-label (tr "labels.close")
                :on-click close-panel}
       "×"]]

     ;; Scope selector
     [:div {:class (stl/css :assistant-scopes)}
      (for [[id label] [[:selection (tr "blockdesign.assistant.scope-selection" "Selección")]
                        [:page (tr "blockdesign.assistant.scope-page" "Página")]
                        [:new (tr "blockdesign.assistant.scope-new" "Nuevo")]]]
        [:button {:key (name id)
                  :type "button"
                  :class (stl/css-case :active (= id scope))
                  :on-click #(reset! scope* id)}
         label])]

     ;; DESIGN.md Action
     [:button {:type "button"
               :class (stl/css :design-md-button)
               :disabled busy?
               :on-click on-design-md}
      "Generar DESIGN.md"]

     ;; Reference task selection
     [:div {:class (stl/css :jira-selector-box)}
      [:label {:class (stl/css :jira-selector-label)} "Vincular Tarea de Jira (Contexto o Evidencia)"]
      (if selected-jira
        [:div {:class (stl/css :jira-selected-badge)}
         [:span (str "[" (:id selected-jira) "] " (:title selected-jira))]
         [:button {:type "button" :on-click #(reset! selected-jira* nil)} "×"]]
        [:div {:class (stl/css :jira-search-input-wrapper)}
         [:input {:type "text"
                  :placeholder "Buscar tarea Jira (ej: DA-45)..."
                  :value jira-query
                  :on-change on-jira-search-change
                  :class (stl/css :jira-search-input)}]])
      (when (and (not selected-jira) (not (empty? jira-results)))
        [:ul {:class (stl/css :jira-results-list)}
         (for [t (take 5 jira-results)]
           [:li {:key (:id t)
                 :on-click #(do (reset! selected-jira* t)
                                (reset! jira-query* "")
                                (reset! jira-results* []))}
            [:strong (str "[" (:id t) "] ")]
            (:title t)])])]

     ;; Share evidence button when Jira is linked
     (when selected-jira
       [:button {:type "button"
                 :class (stl/css :jira-share-button)
                 :disabled busy?
                 :on-click share-to-jira!}
        "📎 Publicar captura en Jira"])

     ;; Checkbox to use DESIGN.md as reference rules
     (when-not (str/blank? current-design-md)
       [:div {:class (stl/css :design-md-reference-checkbox)}
        [:input {:type "checkbox"
                 :id "use-design-md-ref"
                 :checked use-design-md
                 :on-change #(reset! use-design-md* (.. % -target -checked))}]
        [:label {:for "use-design-md-ref"} "Usar DESIGN.md como reglas de estilo"]])

     [:p {:class (stl/css :assistant-context)}
      (if (= scope :page)
        (tr "blockdesign.assistant.page-context" (str (count selection) " capas de la página actual") (count selection))
        (tr "blockdesign.assistant.selection-context" (str (count selection) " capas en contexto") (count selection)))]

     [:div {:class (stl/css :assistant-messages)}
      (for [[index {:keys [kind text]}] (map-indexed vector messages)]
        [:article {:key index
                   :class (stl/css-case :message true
                                        :user (= kind :user)
                                        :error (= kind :error))}
         text])
      (when busy?
        [:article {:class (stl/css :working)}
         (tr "blockdesign.assistant.working" "Generando respuesta y aplicando cambios...")])]

     [:form {:class (stl/css :assistant-form)
             :on-submit on-send}
      [:textarea {:value prompt
                  :rows 4
                  :disabled busy?
                  :placeholder (tr "blockdesign.assistant.placeholder" "Ej: hace un panel con 3 botones alineados")
                  :on-change #(reset! prompt* (.. % -target -value))}]
      [:button {:type "submit"
                :disabled (or busy? (str/blank? prompt))}
       (tr "blockdesign.assistant.send" "Enviar al agente")]]

     ;; The DESIGN.md Modal options
     (when show-save-modal
       [:div {:class (stl/css :assistant-modal-backdrop)}
        [:div {:class (stl/css :assistant-modal)}
         [:h3 "Opciones de DESIGN.md"]

         [:div {:class (stl/css :modal-field)}
          [:label "Nombre del Proyecto (Referencia)"]
          [:input {:type "text"
                   :value save-project-name
                   :placeholder "Ej: Landing Page"
                   :on-change #(reset! save-project-name* (.. % -target -value))}]]

         [:div {:class (stl/css :modal-field)}
          [:label "Descripción"]
          [:input {:type "text"
                   :value save-description
                   :placeholder "Ej: Componentes de cabecera"
                   :on-change #(reset! save-description* (.. % -target -value))}]]

         [:div {:class (stl/css :modal-checkbox-row)}
          [:input {:type "checkbox"
                   :id "save-to-lib"
                   :checked save-to-library
                   :on-change #(reset! save-to-library* (.. % -target -checked))}]
          [:label {:for "save-to-lib"} "Guardar en biblioteca de Daily Assistant"]]

         [:div {:class (stl/css :modal-checkbox-row)}
          [:input {:type "checkbox"
                   :id "define-hierarchy"
                   :checked define-hierarchy
                   :on-change #(reset! define-hierarchy* (.. % -target -checked))}]
          [:label {:for "define-hierarchy"} "Definir jerarquía de componentes y páginas"]]

         [:div {:class (stl/css :modal-actions)}
          [:button {:type "button"
                    :class (stl/css :modal-cancel)
                    :on-click #(reset! show-save-modal* false)}
           "Cancelar"]
          [:button {:type "button"
                    :class (stl/css :modal-confirm)
                    :disabled (and save-to-library (str/blank? save-project-name))
                    :on-click trigger-generate-design-md!}
           "Generar"]]]])]))
