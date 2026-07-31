# BlockDesign v2.0

BlockDesign is Blockmaker's fully versioned design platform fork. The current
base is Penpot `2.17.0`, distributed under MPL-2.0, with Blockmaker-owned source
builds, branding, OIDC integration, deployment configuration, and native AI
assistant.

The production images are built from this repository. Do not replace them with
`penpotapp/frontend`, `penpotapp/backend`, or `penpotapp/exporter`, and do not
build old source with a mutable remote `penpotapp/devenv:latest` image.

## BlockDesign development and deployment

### Source and branches

- Upstream remote: `https://github.com/penpot/penpot.git`
- Blockmaker remote: `https://github.com/BlockmakerCompany/blockdesign`
- Current upstream base: tag `2.17.0`
- Current BlockDesign branch: `blockdesign-2.0-core-2.17`
- Previous protected branch: `blockdesign-2.0-core-2.16`
- Production image namespace: `ghcr.io/blockmakercompany`

Keep upstream code and BlockDesign changes as separate commits. This makes
upgrades reviewable and prevents generated assets or compatibility workarounds
from being carried into a new upstream release.

### BlockDesign customizations

- User-facing branding is changed to **BlockDesign v2.0** only in generated
  frontend bundles. Internal namespaces, file formats, API names, database
  structures, and compatibility identifiers remain unchanged.
- Daily Assistant provides OIDC authentication.
- The workspace includes a native AI assistant in:
  `frontend/src/app/main/ui/workspace/blockdesign_assistant.cljs`.
- The frontend image packages `/assistant-api/` proxying to the Daily Assistant
  backend through `docker/images/files/nginx-blockdesign.conf`.
- Renderer, worker, frontend, backend, and exporter artifacts are compiled from
  this repository. Do not copy renderer assets from an upstream runtime image.

### Prerequisites

- Docker with BuildKit
- Git
- At least 20 GB of free disk space
- At least 8 GB of RAM; 16 GB is recommended

The build toolchain image is approximately 5 GB.

### Initial checkout

```bash
git clone https://github.com/BlockmakerCompany/blockdesign.git
cd blockdesign
git remote add upstream https://github.com/penpot/penpot.git
git fetch upstream --tags
git switch blockdesign-2.0-core-2.17
```

### Build the pinned toolchain

Build the development image from the same source revision before compiling any
application bundle:

```bash
docker build \
  -t ghcr.io/blockmakercompany/blockdesign-devenv:2.17.0 \
  -t ghcr.io/blockmakercompany/blockdesign-devenv:latest \
  docker/devenv
```

`manage.sh` defaults to this Blockmaker image. Override it only when testing a
deliberately versioned toolchain:

```bash
BLOCKDESIGN_DEVENV_IMAGE=ghcr.io/blockmakercompany/blockdesign-devenv \
  ./manage.sh build-frontend-bundle
```

### Build application bundles

```bash
./manage.sh build-frontend-bundle
./manage.sh build-backend-bundle
./manage.sh build-exporter-bundle
```

The frontend build also compiles the Rust/WASM renderer, workers, plugins, and
MCP plugin. Branding runs against `target/dist` after compilation so the source
tree and internal compatibility names are not rewritten.

### Build owned runtime images

```bash
export BLOCKDESIGN_VERSION=2.17.0

./manage.sh build-frontend-docker-image
./manage.sh build-backend-docker-image
./manage.sh build-exporter-docker-image
```

This produces:

```text
ghcr.io/blockmakercompany/blockdesign-frontend:2.17.0
ghcr.io/blockmakercompany/blockdesign-backend:2.17.0
ghcr.io/blockmakercompany/blockdesign-exporter:2.17.0
```

Never deploy `latest` in production. Production Compose must reference the
explicit release tag.

### Deploy on the Daily Assistant server

The deployment Compose project lives at `~/apps/daily-ai`.

1. Back up PostgreSQL:

   ```bash
   docker exec penpot_postgres \
     pg_dump -U penpot -d penpot -Fc \
     > "$HOME/penpot-$(date +%Y%m%d-%H%M%S).dump"
   ```

2. Confirm `docker-compose.yml` references the same BlockDesign version for
   frontend, backend, and exporter.

3. Recreate only the BlockDesign services:

   ```bash
   cd ~/apps/daily-ai
   docker compose up -d --force-recreate \
     penpot-backend penpot-exporter blockdesign-frontend
   ```

4. Wait for the backend readiness endpoint before testing the browser:

   ```bash
   until curl -fsS http://127.0.0.1:9001/readyz; do sleep 2; done
   ```

The backend may require 30-60 seconds for database migrations. During that
window Nginx can return a temporary `502`; do not repeatedly recreate the stack.

Do not use `docker compose up -d --build --force-recreate` for routine
BlockDesign deployments. The application images are built from this repository,
while the Daily Assistant Compose project only deploys those versioned images.

### Required production checks

After every deployment verify:

```bash
curl -fsS https://blockdesign.blockmaker.net/ >/dev/null
curl -fsS https://blockdesign.blockmaker.net/readyz
```

Then verify in a real authenticated browser:

1. OIDC login.
2. Existing shape selection and movement.
3. Rectangle, circle, frame, text, and pencil creation.
4. Save persistence after reload.
5. WebSocket notifications.
6. AI assistant response and native shape operations.
7. Jira task search through Daily Assistant.
8. Browser console and container logs contain no new errors.

### Updating the upstream base

Never recompile an old branch with a newer toolchain. Upgrade source and
toolchain together:

```bash
git fetch upstream --tags
git switch -c blockdesign-2.0-core-<version> <upstream-tag>
```

Reapply BlockDesign commits in this order:

1. Workspace AI assistant and layout integration.
2. Assistant translations.
3. Generated-bundle branding.
4. Nginx assistant proxy and runtime configuration.
5. Blockmaker image naming and release documentation.

Build all bundles and images with the new branch before changing production.
Do not carry canvas, renderer, or pointer-event patches unless they are still
reproducible against the new upstream release.

### Licensing

BlockDesign preserves the MPL-2.0 license and upstream copyright notices.
Modified MPL-covered files remain available in this repository. Blockmaker
branding and integrations do not remove upstream attribution or change the
license obligations.

---

## Upstream project documentation

The remaining README is the upstream project documentation retained for
architecture, contribution, and licensing context.

<img width="100%" src="https://github.com/user-attachments/assets/da17b160-f289-436f-b140-972083a08602" />

[uri_license]: https://www.mozilla.org/en-US/MPL/2.0
[uri_license_image]: https://img.shields.io/badge/MPL-2.0-blue.svg

<p align="center">
  <a href="https://www.digitalpublicgoods.net/r/penpot" rel="nofollow">
    <img alt="Verified DPG" src="https://img.shields.io/badge/Verified-DPG-3333AB?logo=data:image/svg%2bxml;base64,PHN2ZyB3aWR0aD0iMzEiIGhlaWdodD0iMzMiIHZpZXdCb3g9IjAgMCAzMSAzMyIgZmlsbD0ibm9uZSIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj4KPHBhdGggZD0iTTE0LjIwMDggMjEuMzY3OEwxMC4xNzM2IDE4LjAxMjRMMTEuNTIxOSAxNi40MDAzTDEzLjk5MjggMTguNDU5TDE5LjYyNjkgMTIuMjExMUwyMS4xOTA5IDEzLjYxNkwxNC4yMDA4IDIxLjM2NzhaTTI0LjYyNDEgOS4zNTEyN0wyNC44MDcxIDMuMDcyOTdMMTguODgxIDUuMTg2NjJMMTUuMzMxNCAtMi4zMzA4MmUtMDVMMTEuNzgyMSA1LjE4NjYyTDUuODU2MDEgMy4wNzI5N0w2LjAzOTA2IDkuMzUxMjdMMCAxMS4xMTc3TDMuODQ1MjEgMTYuMDg5NUwwIDIxLjA2MTJMNi4wMzkwNiAyMi44Mjc3TDUuODU2MDEgMjkuMTA2TDExLjc4MjEgMjYuOTkyM0wxNS4zMzE0IDMyLjE3OUwxOC44ODEgMjYuOTkyM0wyNC44MDcxIDI5LjEwNkwyNC42MjQxIDIyLjgyNzdMMzAuNjYzMSAyMS4wNjEyTDI2LjgxNzYgMTYuMDg5NUwzMC42NjMxIDExLjExNzdMMjQuNjI0MSA5LjM1MTI3WiIgZmlsbD0id2hpdGUiLz4KPC9zdmc+Cg==">
  </a>
  <a href="https://community.penpot.app" rel="nofollow">
    <img alt="Penpot Community" src="https://img.shields.io/discourse/posts?server=https%3A%2F%2Fcommunity.penpot.app">
  </a>
  <a href="https://tree.taiga.io/project/penpot/" rel="nofollow">
    <img alt="Managed with Taiga.io" src="https://img.shields.io/badge/managed%20with-TAIGA.io-709f14.svg">
  </a>
  <a href="https://gitpod.io/#https://github.com/penpot/penpot" rel="nofollow">
    <img alt="Gitpod ready-to-code" src="https://img.shields.io/badge/Gitpod-ready--to--code-blue?logo=gitpod">
  </a>
</p>

<p align="center">
  <a href="https://penpot.app/"><b>Website</b></a>  •
  <a href="https://help.penpot.app/user-guide/"><b>User Guide</b></a>  •
  <a href="https://penpot.app/learning-center"><b>Learning Center</b></a>  •
  <a href="https://community.penpot.app/"><b>Community</b></a>
</p>
<p align="center">
  <a href="https://www.youtube.com/@Penpot"><b>Youtube</b></a>  •
  <a href="https://peertube.kaleidos.net/a/penpot_app/video-channels"><b>Peertube</b></a>  •
  <a href="https://www.linkedin.com/company/penpot/"><b>Linkedin</b></a>  •
  <a href="https://instagram.com/penpot.app"><b>Instagram</b></a>  •
  <a href="https://fosstodon.org/@penpot/"><b>Mastodon</b></a>  •
  <a href="https://bsky.app/profile/penpot.app"><b>Bluesky</b></a>  •
  <a href="https://twitter.com/penpotapp"><b>X</b></a>
</p>

[Penpot video](https://github.com/user-attachments/assets/7c67fd7c-04d3-4c9b-88ec-b6f5e23f8332)

Penpot is the open-source design platform for teams that build digital products at scale.

Penpot’s key strength lies in giving you **full ownership of your design infrastructure**. Built on open source and designed for [self-hosting](https://help.penpot.app/technical-guide/getting-started/), it puts teams in complete control of their design environment supporting strict compliance and governance requirements. Whether used in the **browser or deployed on your own servers**, Penpot **works with open standards** like SVG, CSS, HTML, and JSON. 

Real-time collaboration strengthens this foundation, helping teams scale and bring design closer to the product through top-tier capabilities. Additionally, developers feel at home using Penpot, because design is expressed as code, enabling a direct translation and shipping products faster. 

Best-in-class native [Design Tokens](https://penpot.dev/collaboration/design-tokens) provide a single source of truth between design and development. They ensure consistency, improve collaboration, and make it easier to manage complex design systems.

The [MCP server](https://penpot.app/penpot-mcp-server) takes it further by enabling multi-directional workflows between design and code. A [powerful open API](https://help.penpot.app/mcp/#quick-start) and plugin system makes the workspace programmable, enabling automation, AI-driven workflows, and integrations with the tools and systems you already use.

With [CSS Grid and Flex Layout](https://help.penpot.app/user-guide/designing/flexible-layouts/), teams can design responsive interfaces that behave like real code from the start.

Combined, these features turn Penpot into a **full-stack design platform** for building scalable design systems and fully integrated product development processes.

If your organization is scaling and needs extra support, we’re here to help. [Talk to us](https://penpot.app/talk-to-us)

## Table of contents ##

- [Why Penpot](#why-penpot)
- [Getting Started](#getting-started)
- [Community](#community)
- [Contributing](#contributing)
- [Resources](#resources)
- [License](#license)

## Why Penpot ##

Penpot connects design, code, and AI workflows through a code-based approach, making designs readable by developers and AI via the MCP server. This approach helps teams ship what’s actually designed and manage design systems at scale with powerful design tokens. As a self-hosted, open-source and real-time collaboration platform, Penpot offers full flexibility, security, and ownership without vendor lock-in. Learn more about [why Penpot](https://penpot.app/why-penpot) is the platform for your team.

### Plugin system ###

[Penpot plugins](https://penpot.app/penpothub/plugins) let you expand the platform's capabilities, give you the flexibility to integrate it with other apps, and design custom solutions.

### Designed for developers ###

Penpot was built to serve both designers and developers and create a fluid design-code process. You have the choice to enjoy real-time collaboration or play "solo".

### Inspect mode ###

Work with ready-to-use code and make your workflow easy and fast. The inspect tab gives instant access to SVG, CSS and HTML code.

### Integrations ###

Penpot offers [integration](https://penpot.app/integrations-api) into the development toolchain, thanks to its support for webhooks and an API accessible through access tokens.

### Building Design Systems: design tokens, components and variants ###

Penpot brings [design systems](https://penpot.app/design/design-systems) to code-minded teams: a single source of truth with native Design Tokens, Components, and Variants for scalable, reusable, and consistent UI across projects and platforms.

<img width="100%" alt="Penpot Design Systems" src="https://github.com/user-attachments/assets/cce75ad6-f783-473f-8803-da9eb8255fef">

## Getting started ##

Penpot is the only design & prototype platform that is deployment agnostic. You can use it in our [SAAS](https://design.penpot.app) or deploy it anywhere.

Learn how to install it with Docker, Kubernetes, Elestio or other options on [our website](https://penpot.app/self-host).

## Community ##

We love the Open Source software community. Contributing is our passion and if it’s yours too, participate and [improve](https://community.penpot.app/c/help-us-improve-penpot/7) Penpot. All your designs, code and ideas are welcome!

Want to go a step further? Become a [Penpot Ambassador](https://penpot.app/ambassador-program) and help grow the Penpot community in your region while contributing to a global, open design ecosystem.

If you need help or have any questions; if you’d like to share your experience using Penpot or get inspired; if you’d rather meet our community of developers and designers, [join our Community](https://community.penpot.app/)!

Categories include:

- [Ask the Community](https://community.penpot.app/c/ask-for-help-using-penpot/6)
- [Troubleshooting](https://community.penpot.app/c/technical/8)
- [Help us Improve Penpot](https://community.penpot.app/c/help-us-improve-penpot/7)
- [Events and Announcements](https://community.penpot.app/c/announcements/5)
- [Penpot in your language](https://community.penpot.app/c/penpot-in-your-language/12)
- [Education](https://community.penpot.app/c/education/28)

<img width="100%" alt="Pentpot Community" src="https://github.com/user-attachments/assets/4b2a4360-12b5-4994-bd45-641449f86c4e" />

### Code of Conduct ###

Anyone who contributes to Penpot, whether through code, in the community, or at an event, must adhere to the
[code of conduct](https://help.penpot.app/contributing-guide/coc/) and foster a positive and safe environment.

### Contributing ###

Any contribution will make a difference to improve Penpot. How can you get involved?

Choose your way:

- Create and [share Libraries & Templates](https://penpot.app/libraries-templates.html) that will be helpful for the community.
- Invite your [team to join](https://design.penpot.app/#/auth/register).
- Give this repo a star and follow us on Social Media: [Mastodon](https://fosstodon.org/@penpot/), [Youtube](https://www.youtube.com/c/Penpot), [Instagram](https://instagram.com/penpot.app), [Linkedin](https://www.linkedin.com/company/penpotdesign),  [Peertube](https://peertube.kaleidos.net/a/penpot_app), [X](https://twitter.com/penpotapp) and [BlueSky](https://bsky.app/profile/penpot.app).
- Participate in the [Community](https://community.penpot.app/) space by asking and answering questions; reacting to others’ articles;  opening your own conversations and following along on decisions affecting the project.
- Report bugs with our easy [guide for bugs hunting](https://help.penpot.app/contributing-guide/reporting-bugs/) or [GitHub issues](https://github.com/penpot/penpot/issues).
- Become a [translator](https://help.penpot.app/contributing-guide/translations).
- Give feedback: [Email us](mailto:support@penpot.app).
- **Contribute to Penpot's code:** [Watch this video](https://www.youtube.com/watch?v=TpN0osiY-8k) by Alejandro Alonso, CIO and developer at Penpot, where he gives us a hands-on demo of how to use Penpot’s repository and make changes in both front and back end.

To find (almost) everything you need to know on how to contribute to Penpot, refer to the [contributing guide](https://help.penpot.app/contributing-guide/).

<img width="100%" alt="Penpot hub" src="https://github.com/user-attachments/assets/0abc02f0-625c-45ab-ad81-4927bec7a055" />

## Resources ##

You can ask and answer questions, have open-ended conversations, and follow along on decisions affecting the project.

💾 [Documentation](https://help.penpot.app/technical-guide/)

🚀 [Getting Started](https://help.penpot.app/technical-guide/getting-started/)

✏️ [Tutorials](https://www.youtube.com/playlist?list=PLgcCPfOv5v54WpXhHmNO7T-YC7AE-SRsr)

🏘️ [Architecture](https://help.penpot.app/technical-guide/developer/architecture/)

📚 [Dev Diaries](https://penpot.app/dev-diaries.html)

🧑‍🏫​ [UI Design Course](https://penpot.app/courses/)


## License ##

```
This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/.

Copyright (c) KALEIDOS INC Sucursal en España SL
```
Penpot is a Kaleidos’ [open source project](https://kaleidos.net/)
