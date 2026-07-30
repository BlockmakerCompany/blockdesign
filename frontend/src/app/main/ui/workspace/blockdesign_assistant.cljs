;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.main.ui.workspace.blockdesign-assistant
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.shape.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.texts :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
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
           :children (mapv str (:shapes shape))
           :fills (mapv #(select-keys % [:fill-color :fill-opacity :fill-color-gradient]) (:fills shape))
           :strokes (mapv #(select-keys % [:stroke-color :stroke-opacity :stroke-width :stroke-style]) (:strokes shape))}
    (= :text (:type shape))
    (assoc :text (some-> (:content shape) txt/content->text))))

(defn- context-shapes
  [scope selected objects]
  (let [ids (if (= scope :page)
              (->> objects keys (remove uuid/zero?))
              (mapcat (fn collect-ids [id]
                        (cons id (mapcat collect-ids (get-in objects [id :shapes]))))
                      selected))]
    (->> ids
         distinct
         (keep #(get objects %))
         (take max-context-shapes)
         (mapv serialize-shape))))

(defn- operation-id
  [operation]
  (some-> (:id operation) uuid/parse*))

(defn- update-shape
  [shape operation]
  (let [fill (:fill operation)
        stroke (:stroke operation)]
    (cond-> shape
      (string? (:name operation))
      (assoc :name (:name operation))

      (number? (:opacity operation))
      (assoc :opacity (max 0 (min 1 (:opacity operation))))

      (number? (:rotation operation))
      (assoc :rotation (:rotation operation))

      (and (string? fill) (not (str/blank? fill)))
      (assoc :fills [{:fill-color fill :fill-opacity 1}])

      (and (string? stroke) (not (str/blank? stroke)))
      (assoc :strokes [{:stroke-color stroke
                        :stroke-opacity 1
                        :stroke-width (or (:strokeWidth operation) 1)
                        :stroke-style :solid}]))))

(defn- apply-operation!
  [operation objects]
  (let [action (keyword (:action operation))
        id (operation-id operation)
        shape (get objects id)]
    (case action
      :update
      (when shape
        (st/emit! (dwsh/update-shapes [id] #(update-shape % operation)))
        (when (and (= :text (:type shape)) (string? (:text operation)))
          (let [current-text (txt/content->text (:content shape))]
            (st/emit! (dwt/replace-text-in-shapes [id] current-text (:text operation)))))
        true)

      :remove
      (when id
        (st/emit! (dwsh/delete-shapes #{id}))
        true)

      :create
      (let [shape-type (case (:type operation)
                         "rectangle" :rect
                         "ellipse" :circle
                         "board" :frame
                         "text" :text
                         nil)]
        (when shape-type
          (st/emit!
           (dwsh/create-and-add-shape
            shape-type
            (or (:x operation) 0)
            (or (:y operation) 0)
            (cond-> {:name (or (:name operation) (tr "blockdesign.assistant.new-layer"))
                     :x (or (:x operation) 0)
                     :y (or (:y operation) 0)
                     :width (max 1 (or (:width operation) 160))
                     :height (max 1 (or (:height operation) 80))}
              (string? (:fill operation))
              (assoc :fills [{:fill-color (:fill operation) :fill-opacity 1}]))))
          true))

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
    (rx/subs! request
              (fn [{:keys [status body]}]
                (let [data (js->clj body :keywordize-keys true)]
                  (if (<= 200 status 299)
                    (on-success data)
                    (on-error (or (:error data) (tr "blockdesign.assistant.request-error" status))))))
              (fn [error]
                (on-error (or (ex-message error) (tr "blockdesign.assistant.network-error")))))))

(mf/defc assistant-panel*
  []
  (let [profile (mf/deref refs/profile)
        page (mf/deref refs/workspace-page)
        objects (mf/deref refs/workspace-page-objects)
        selected (mf/deref refs/selected-shapes)

        scope* (mf/use-state :selection)
        prompt* (mf/use-state "")
        messages* (mf/use-state [{:kind :assistant :text (tr "blockdesign.assistant.welcome")}])
        busy* (mf/use-state false)

        scope (deref scope*)
        prompt (deref prompt*)
        messages (deref messages*)
        busy? (deref busy*)
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
         :pageName (or (:name page) (tr "blockdesign.assistant.page"))
         :selection selection}

        on-send
        (mf/use-fn
         (mf/deps prompt context profile objects busy?)
         (fn [event]
           (.preventDefault event)
           (when (and (not busy?) (not (str/blank? prompt)))
             (let [submitted prompt]
               (reset! prompt* "")
               (reset! busy* true)
               (add-message! :user submitted)
               (request-assistant!
                {:action "edit" :prompt submitted :context context}
                profile
                (fn [data]
                  (let [changed (count (filter true? (map #(apply-operation! % objects) (:operations data))))]
                    (add-message! :assistant
                                  (or (:summary data)
                                      (tr "blockdesign.assistant.applied" changed)))
                    (reset! busy* false)))
                (fn [message]
                  (add-message! :error message)
                  (reset! busy* false)))))))

        on-design-md
        (mf/use-fn
         (mf/deps context profile busy?)
         (fn []
           (when-not busy?
             (reset! busy* true)
             (request-assistant!
              {:action "design-md" :context context}
              profile
              (fn [data]
                (download-design-md! (or (:content data) ""))
                (add-message! :assistant (tr "blockdesign.assistant.design-md-ready"))
                (reset! busy* false))
              (fn [message]
                (add-message! :error message)
                (reset! busy* false))))))]

    [:section {:class (stl/css :assistant-panel)}
     [:header {:class (stl/css :assistant-header)}
      [:div
       [:h2 (tr "blockdesign.assistant.title")]
       [:p (tr "blockdesign.assistant.subtitle")]]
      [:button {:type "button"
                :aria-label (tr "labels.close")
                :on-click close-panel}
       "×"]]

     [:div {:class (stl/css :assistant-scopes)}
      (for [[id label] [[:selection (tr "blockdesign.assistant.scope-selection")]
                        [:page (tr "blockdesign.assistant.scope-page")]
                        [:new (tr "blockdesign.assistant.scope-new")]]]
        [:button {:key (name id)
                  :type "button"
                  :class (stl/css-case :active (= id scope))
                  :on-click #(reset! scope* id)}
         label])]

     [:button {:type "button"
               :class (stl/css :design-md-button)
               :disabled busy?
               :on-click on-design-md}
      (tr "blockdesign.assistant.design-md")]

     [:p {:class (stl/css :assistant-context)}
      (if (= scope :page)
        (tr "blockdesign.assistant.page-context" (count selection))
        (tr "blockdesign.assistant.selection-context" (count selection)))]

     [:div {:class (stl/css :assistant-messages)}
      (for [[index {:keys [kind text]}] (map-indexed vector messages)]
        [:article {:key index
                   :class (stl/css-case :message true
                                        :user (= kind :user)
                                        :error (= kind :error))}
         text])
      (when busy?
        [:article {:class (stl/css :working)}
         (tr "blockdesign.assistant.working")])]

     [:form {:class (stl/css :assistant-form)
             :on-submit on-send}
      [:textarea {:value prompt
                  :rows 4
                  :disabled busy?
                  :placeholder (tr "blockdesign.assistant.placeholder")
                  :on-change #(reset! prompt* (.. % -target -value))}]
      [:button {:type "submit"
                :disabled (or busy? (str/blank? prompt))}
       (tr "blockdesign.assistant.send")]]]))
