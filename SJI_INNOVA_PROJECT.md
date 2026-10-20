# SJI INNOVA — PROJECT MASTER FILE

> **Single source of truth** for the team and for any AI assistant.
> Keep it short, current and honest. If a decision changes, change it here first.
> **Document version:** v0.2 — 2026-10-20

**Project working name:** `TO FILL` *(e.g. "Innova", "ShopMate", "BizLocal")*
**One-line pitch:** An inventory, sales and customer-credit system for Cameroonian SMEs that keeps working offline and comes with a WhatsApp AI assistant that answers from live stock data.
**Submission deadline:** `TO FILL` **Competition date:** `TO FILL`
**Last updated:** 2026-10-20 **Maintained by:** `TO FILL` *(project lead)*

---

## 0. How to use this file

**Humans:** read sections 1, 3, 7, 8 and 12 every Monday. Update section 16 after every work session.

**AI assistants:** this file is the project context. Before answering any request:
1. Respect the decisions in sections 3 and 4. Do not propose new technologies unless asked.
2. Work on one task from section 8 at a time.
3. Explain code simply — the team is learning. Give small, runnable steps.
4. If something is marked `TO FILL`, ask for the value instead of assuming.

---

## 1. Vision and positioning

**Problem.** Many Cameroonian small and medium businesses manage stock, sales and customer debts on paper, Excel or from memory. Foreign tools (Odoo, Zoho, Sage…) exist but assume stable internet, card payments and foreign accounting habits.

**Our answer.** A tool built for local reality:
- Works **offline-first** (internet and power cuts are normal)
- **Credit sales and customer debts** are a core feature
- **Mobile Money** payments recorded properly (reference code, pending/confirmed status, owner reconciliation)
- **FCFA, VAT and French/English** from day one
- **WhatsApp AI assistant** that answers customers and the owner from real stock data
- Simple and affordable for a small shop

**Why we win.** Not "more features than Odoo" but "the only one that understands a Cameroonian shop", plus a live, reliable demo.

**Positioning rule.** Target = small and medium businesses (shops, pharmacies, wholesalers, restaurants). Larger companies are a roadmap item shown through scalable architecture, not claimed today.

---

## 2. Users and problems *(validate with interviews)*

| User | Interface | Main need |
|---|---|---|
| Cashier | Desktop app | Fast sales, receipt printing, works offline |
| Owner / manager | Owner dashboard (mobile-friendly web, installable as PWA) | Stock levels, sales, debts, alerts — accessible on phone |
| Customer | WhatsApp | "Do you have X? What's the price?" |

> **Note on mobile:** A native mobile app is out of scope (see §3). The owner dashboard is a **Progressive Web App (PWA)** — a web page the owner installs on their phone. It caches data locally so it loads after the first visit even without internet. This closes the offline gap for the owner without requiring a separate mobile app.

**Interview plan (Weeks 1-2):** each team member interviews 2 shop owners (~10 total). Questions:
- What costs you the most today: stock-outs, theft, unpaid debts, not knowing your profit?
- How often do internet and power fail? What do you do then?
- How do you record sales and debts now? What device do you use?
- How do customers contact you (phone, WhatsApp)? How do they pay (cash, MoMo, credit)?
- Would you pay for a tool that solves the top problem? How much per month?

Record results in `/research/interviews.md` (one block per interview) and keep the top 3 pains here:

1. *(fill after interviews)*
2.
3.

---

## 3. Scope

### MVP — must work perfectly in the demo
- [ ] Products and categories, stock levels, low-stock alerts
- [ ] Sales (cash, Mobile Money reference, on credit) with receipt printing
- [ ] **Sales void/correction** — cashier can cancel a mistaken sale with reason and audit trail
- [ ] **Discounts** per sale line (e.g. "500 FCFA off")
- [ ] Customer debts and repayments, printable debt statement
- [ ] Users and roles (owner, cashier) with login
- [ ] Offline mode on the desktop app with background sync to the cloud
- [ ] **Daily cash reconciliation** — expected cash (from sales) vs actual cash counted by the owner
- [ ] Owner dashboard: daily/weekly sales, top products, low stock, outstanding debts *(PWA, works on phone)*
- [ ] WhatsApp AI agent: customer mode (availability, price) and owner mode (sales summary, low stock, debt reminders)
- [ ] French and English interface (all strings externalized)
- [ ] **Barcode/SKU scanner** support (USB scanners, keyboard-input mode)
- [ ] **CSV bulk import** for products (so shops can migrate from Excel)

### Phase 2 — roadmap slide, not built
- Staff management (attendance, sales per employee)
- Light finance module (expenses, cash in/out, simple P&L)
- Multi-branch, supplier orders, advanced reports
- Mobile Money webhook for automatic pending→paid reconciliation
- Supplier catalogue and purchase orders

### Phase 3 — vision
- Mobile Money API full integration, larger companies, full accounting/payroll compliance

### Explicitly out of scope
Full payroll, tax declarations, large-enterprise ERP features, native mobile app (replaced by PWA).

---

## 4. Architecture and stack *(decisions)*

**Core principle:** the shop works offline; the cloud and the AI work online.

| Layer | Choice | Notes |
|---|---|---|
| Desktop app | **Java Swing + FlatLaf** | Cashier interface; FlatLaf gives a modern look |
| Local DB | **SQLite** via JDBC | Embedded, nothing to install, safe after power cuts |
| Cloud API | **`TO FILL`**: Spring Boot **or** Javalin | ⚠️ Decide by end of Week 1 — email the teacher. Use Javalin if Spring Boot was not covered in class. |
| Cloud DB | **MySQL** | Known by the team |
| Owner dashboard | **HTML + CSS + Chart.js**, served as a **PWA** | Hosted as static files on the API server or a free host (e.g. Netlify). Uses a `manifest.json` + service worker for offline caching. |
| AI agent | **n8n** (visual workflow, no extra language needed) | Calls the cloud API only — never invents data |
| Receipt printing | **ESC/POS** via `escpos-java` or USB print | Works with most thermal printers common in Cameroon |
| Packaging | `jpackage` installer | Bundles Java runtime — nothing to install on the cashier PC |

**Why n8n over Python:** no Python developer is assigned; n8n requires no extra language, has a visual editor, and can be self-hosted for free. If n8n is unavailable during the demo, a simulated flow is the fallback.

**Code layers (mandatory):**
1. **UI (Swing)** — only displays data and collects input, no business logic
2. **Services** — all business rules (sell, receive stock, record debt, compute VAT)
3. **Data layer (JDBC)** — the only code touching the database; returns/accepts plain Java objects

**Sync rules:**
- Record **movements** (sale, delivery, adjustment, void, return), never overwrite a stock total. `current_stock = SUM(stock_movements)` is recalculated and cached in the `products` table after each sync.
- Every record has a locally generated **UUID** and a `device_id`; the server ignores UUIDs it already has (idempotency).
- Local writes are immediate and crash-safe; sync runs in a background thread when internet is available.
- Negative stock after sync is flagged to the owner via WhatsApp notification and shown on the dashboard — never hidden.
- **Conflict rule for catalog** (prices, product names): last write wins; the owner has authority.
- **Conflict rule for sales**: both are kept; if both devices sold the last unit, the owner is notified and stock goes negative with a clear flag.

**Dashboard hosting rule:**
The dashboard HTML/JS/CSS files are served either (a) as static files from the same Java server or (b) from a free static host. The Java API handles only JSON endpoints. This separation avoids mixing concerns and simplifies deployment.

**AI agent rules:**
- Reads through defined API endpoints only — no invented prices or stock levels.
- Two access modes controlled by separate API tokens:
  - **Customer token:** can query product availability and price only.
  - **Owner token:** can query sales summaries, low-stock alerts, debt totals, and trigger debt reminder messages. Margins and cost prices are never exposed to the customer token.
- If data is stale (shop offline), the answer states "stock as of HH:MM" and offers to notify the owner.

---

## 5. Data model *(starting point — refine with the team)*

| Table | Key fields |
|---|---|
| `shops` | `id` (UUID), `name`, `currency` (default FCFA), `language`, `vat_rate` (default 19.25% Cameroon) |
| `users` | `id`, `shop_id`, `name`, `role` (owner / cashier), `password_hash`, `created_at` |
| `sessions` | `id`, `user_id`, `token_hash`, `expires_at`, `created_at` *(API authentication)* |
| `products` | `id`, `shop_id`, `name`, `sku_barcode`, `category`, `unit_price`, `cost_price`, `tax_rate`, `min_stock`, `current_stock` *(cached snapshot, updated after sync)*, `image_path` (optional) |
| `stock_movements` | `id`, `product_id`, `type` (sale / delivery / adjustment / void / return), `quantity` (+/-), `device_id`, `created_at`, `synced` |
| `sales` | `id`, `shop_id`, `user_id`, `customer_id` (nullable), `subtotal`, `tax_amount`, `total`, `discount_total`, `payment_type` (cash / momo / credit), `momo_reference`, `status` (paid / pending / credit / voided), `voided_by_user_id` (nullable), `void_reason` (nullable), `created_at`, `synced` |
| `sale_items` | `id`, `sale_id`, `shop_id`, `product_id`, `quantity`, `unit_price`, `tax_rate`, `discount_amount` |
| `customers` | `id`, `shop_id`, `name`, `phone`, `created_at` |
| `debt_payments` | `id`, `customer_id`, `sale_id` (nullable), `amount`, `created_at`, `synced` |
| `devices` | `id`, `shop_id`, `name`, `last_sync_at` |
| `audit_log` | `id`, `shop_id`, `user_id`, `action` (e.g. `VOID_SALE`, `EDIT_PRICE`, `DELETE_PRODUCT`), `target_table`, `target_id`, `old_value` (JSON), `new_value` (JSON), `created_at` |
| `cash_reconciliations` | `id`, `shop_id`, `user_id`, `session_date`, `expected_cash`, `actual_cash`, `difference`, `notes`, `created_at` |

**Global rules:**
- UUID primary keys everywhere — no auto-increment integers.
- `created_at` on every table.
- `shop_id` on `sale_items` (for future multi-branch queries without joins).
- `tax_rate` stored on each `sale_item` at the time of sale — never recalculate from the current product (prices change).
- FCFA has no sub-unit: all amounts are stored as `INTEGER` (no decimals).

---

## 6. Team and roles *(5 people)*

| Role | Owner | Responsibilities | Backup for |
|---|---|---|---|
| Project lead & integration | `TO FILL` | Planning, this file, merges, unblocking the team | Backend |
| Backend & database | `TO FILL` | Cloud API, MySQL, sync endpoints, JWT auth | Desktop app |
| Desktop app | `TO FILL` | Swing screens, SQLite, offline mode, receipt print | Backend |
| AI agent & dashboard | `TO FILL` | n8n flows, PWA dashboard, WhatsApp integration | Research/QA |
| Research, design, QA & pitch | `TO FILL` | Interviews, mockups, testing the demo path, slides | AI agent |

**Rule:** every person owns one area and knows one backup area. Everyone must understand the full end-to-end flow before the demo.

---

## 7. Timeline *(9 weeks, AI-assisted)*

> AI tools (Antigravity IDE, Claude, ChatGPT) compress boilerplate and code generation by ~40%. They do **not** compress real testing, interviews, or integration work. Plan accordingly.

**Idea submission deadline:** `TO FILL`
**Competition date:** `TO FILL`

### Week 1 — Lock & Setup
| Goal | Output |
|---|---|
| Fill all `TO FILL` items in this file (names, dates, project name) | Completed master file |
| Decide Spring Boot vs Javalin — email teacher | Stack decision logged in §16 |
| Create Git repo, branches rule (`main` stable, one branch per feature) | Working repo |
| Set up Java toolchain, SQLite, MySQL on all machines | All 5 devs on the same version |
| Start interviews (5 this week) | 5 interview notes in `/research/interviews.md` |
| **AI task:** generate full DDL (SQLite + MySQL) from §5 | Reviewed, committed schema files |

### Week 2 — Research + Core Data Layer
| Goal | Output |
|---|---|
| Finish 10 interviews, extract top 3 pains | Problem statement with real quotes |
| Competitor table (Odoo, Zoho Inventory, one local tool) | What they miss for Cameroon |
| **AI task:** generate all JDBC DAO classes + Service layer + unit test skeletons | Reviewed, tested, committed |
| Human task: run every DAO test, understand each class before committing | All unit tests green |

### Week 3 — Desktop UI: Products & Sales
| Goal | Output |
|---|---|
| **AI task:** generate Swing screens — product list, add/edit product, sale cart, payment dialog, receipt | Screens scaffolded |
| Wire UI → service layer | End-to-end: add product → make a sale → stock drops |
| FlatLaf theme, French/English i18n file | Both languages switchable |
| Barcode scanner hook (USB keyboard mode) | Scan triggers product lookup |
| **Milestone:** cashier can complete a full sale (cash, MoMo, credit) on the desktop app offline | ✅ |

### Week 4 — Desktop UI: Customers, Debts, Stock, Login + Submission
| Goal | Output |
|---|---|
| Customer CRUD, debt screen, repayment flow, debt statement print | Debt module working |
| Sales void/correction flow with audit log entry | Void tested |
| Login screen + role-based UI (cashier vs owner views) | Roles enforced |
| Daily cash reconciliation screen | Reconciliation saves to DB |
| CSV import for products | Shop can import from Excel |
| Write submission document (AI drafts, team edits with real data) | Submitted ✅ |
| **Milestone:** full MVP desktop flow works completely offline | ✅ |

### Week 5 — Offline Sync
| Goal | Output |
|---|---|
| Background sync thread with retry and idempotency | Runs when internet available |
| Conflict detection and owner notification | Flag displayed + WhatsApp alert |
| **Stress test:** unplug network, make 20 sales, reconnect, verify cloud data | Zero data loss |
| Update `current_stock` snapshot after each sync | Product list shows correct stock |
| **Milestone:** 10 disconnect/reconnect cycles with no data loss | ✅ |

### Week 6 — Cloud API + Security
| Goal | Output |
|---|---|
| REST endpoints: products, stock, sales summary, debts, customer availability query | All endpoints return JSON |
| JWT auth: owner token vs customer token (different scopes) | Postman tests pass |
| Session management (`sessions` table) | Token expiry works |
| **Milestone:** all API endpoints tested in Postman; customer token cannot access owner data | ✅ |

### Week 7 — Owner Dashboard (PWA)
| Goal | Output |
|---|---|
| HTML/CSS/Chart.js dashboard: sales chart, top products, low stock, debts | Renders on phone browser |
| `manifest.json` + service worker (cache API responses) | Installable as PWA, loads offline after first visit |
| Low-stock push: when stock < min, send WhatsApp message to owner | Owner receives WhatsApp alert |
| **Test:** open dashboard on a real Android phone on slow data | Loads in < 4 seconds |
| **Milestone:** owner installs PWA, views dashboard offline | ✅ |

### Week 8 — WhatsApp AI Agent (n8n)
| Goal | Output |
|---|---|
| n8n self-hosted instance running | n8n accessible |
| Customer flow: availability query + price query | Correct answers from API |
| Owner flow: sales summary, low-stock list, trigger debt reminder to customer | Owner mode working |
| Stale-data message when shop is offline | "Stock as of HH:MM" message shown |
| Fallback: if agent can't answer → "I'll check with the owner" + owner notified | Tested |
| **Milestone:** end-to-end demo path — customer query → correct answer; owner query → correct summary | ✅ |

### Week 9 — Polish, Hardening & Demo
| Goal | Output |
|---|---|
| `jpackage` installer built and tested on 3 different Windows PCs | Installer runs on clean PC |
| French/English strings 100% complete — no missing keys | QA sign-off |
| Slides finalized (problem, solution, demo, roadmap, team, business model) | Deck ready |
| Demo rehearsed 5 times end-to-end, timed | < 3 minutes every run |
| Video recording of full demo as backup | Video on USB key |
| Second laptop with identical data loaded | Ready on demo day |
| **Milestone:** demo runs cleanly 5 times in a row, online and offline | ✅ |

### Build order *(each step must work before the next)*
1. Core desktop: products, sales, stock (Swing + SQLite)
2. Local persistence and crash safety
3. Offline sync (cloud sync, conflict handling)
4. Cloud API (build on the sync endpoints already tested)
5. Owner dashboard (PWA)
6. AI agent (n8n)
7. Installer, polish and demo hardening

---

## 8. Backlog *(check items off, move to §16 when done)*

### M0: Setup (Week 1)
- [ ] Create Git repository; rules: `main` always stable, one branch per feature, PRs reviewed before merge `[lead]`
- [ ] Shared folder for docs, this file, interview notes `[lead]`
- [ ] Chat group + one fixed 30-minute weekly team meeting `[lead]`
- [ ] All 5 developers on the same Java version and IDE `[all]`
- [ ] Decide Spring Boot vs Javalin — log decision in §16 `[lead + backend dev]`
- [ ] **Register WhatsApp Business test number** — start Meta approval immediately (takes days to weeks) `[AI/dashboard dev]`

### M1: Research and submission (Weeks 1-4)
- [ ] 10 interviews done and summarized in `/research/interviews.md` `[research]`
- [ ] Competitor table (Odoo, Zoho Inventory, one local tool) — what they miss for Cameroon `[research]`
- [ ] Clickable mockups: sale screen, stock screen, debts, dashboard, WhatsApp chat `[research/design]`
- [ ] Submission text written, reviewed by 2 people outside the team `[lead]`
- [ ] Prototype 1: modern Swing sale screen with FlatLaf theme `[desktop dev]`
- [ ] Prototype 2: save a sale + stock movement in SQLite via JDBC `[desktop dev]`
- [ ] Prototype 3: upload a completed sale from the app to the cloud API `[backend]`
- [ ] Prototype 4: n8n agent answering "do you have X?" from the cloud DB `[AI/dashboard dev]`

### M2: Core desktop (Weeks 3-4)
- [ ] Products CRUD with barcode/SKU field and scanner hook `[desktop dev]`
- [ ] Stock list with current stock display and low-stock highlight `[desktop dev]`
- [ ] CSV bulk import for products (from Excel export) `[desktop dev]`
- [ ] Sale flow: cart, discount per line, all payment types, receipt print (ESC/POS) `[desktop dev]`
- [ ] Sales void/correction with reason and audit log entry `[desktop dev]`
- [ ] Customers CRUD, debt recording, repayment, printable debt statement `[desktop dev]`
- [ ] Daily cash reconciliation screen `[desktop dev]`
- [ ] Users, roles and login `[desktop dev]`
- [ ] Unit tests for the entire service layer `[desktop dev + backend]`

### M3: Offline and sync (Week 5)
- [ ] `stock_movements` / `sales` with UUID, `device_id`, `synced` flag `[backend]`
- [ ] Background sync thread with retry and idempotency `[backend]`
- [ ] `current_stock` snapshot updated on the product after each sync `[backend]`
- [ ] Conflict detection: negative stock → owner WhatsApp alert `[backend]`
- [ ] Test: unplug internet, make 20 sales, reconnect, verify cloud data — zero loss `[QA]`

### M4: Cloud API and security (Week 6)
- [ ] REST endpoints: products, stock levels, sales summary, debts, customer availability query `[backend]`
- [ ] JWT auth: owner token (full scope) and customer token (read-only: availability + price only) `[backend]`
- [ ] Session table + token expiry `[backend]`
- [ ] Audit log endpoints for owner `[backend]`
- [ ] Permissions test: customer token cannot reach owner endpoints `[QA]`

### M5: Dashboard and AI agent (Weeks 7-8)
- [ ] HTML/CSS/Chart.js dashboard: sales chart, top products, low stock, debts `[AI/dashboard dev]`
- [ ] PWA manifest + service worker (offline cache of last API responses) `[AI/dashboard dev]`
- [ ] Automatic low-stock WhatsApp notification to owner `[AI/dashboard dev]`
- [ ] n8n flow: customer mode — availability, price `[AI/dashboard dev]`
- [ ] n8n flow: owner mode — sales summary, low-stock list, trigger customer debt reminder `[AI/dashboard dev]`
- [ ] Stale-data message when shop offline: "Stock as of HH:MM" `[AI/dashboard dev]`
- [ ] Fallback path: agent cannot answer → notifies owner `[AI/dashboard dev]`

### M6: Polish and delivery (Week 9)
- [ ] `jpackage` installer tested on 3 clean Windows PCs (minimum 4 GB RAM) `[desktop dev]`
- [ ] French/English strings 100% complete, no missing keys `[all + QA]`
- [ ] Demo path rehearsed 5 times, backup plan (video + second laptop) ready `[all]`
- [ ] Slides: problem, solution, demo, architecture, roadmap, business model, team `[research/QA]`
- [ ] One-page project summary printed `[research/QA]`

---

## 9. Quality gates — definition of done

A task is **done** only if all of the following are true:
1. It runs on a **clean Windows machine** (minimum 4 GB RAM, Windows 10/11) from the repo instructions alone.
2. It was **tested by someone other than its author**.
3. The author can **explain the code** without reading it — the jury will ask.
4. **No secrets** (passwords, API keys, tokens) are committed to the repo.
5. It is **merged to `main`** and noted in §16.
6. For any sync-related feature: tested with **network disconnected and reconnected** at least 5 times.

---

## 10. Demo script *(target: 3 minutes)*

| Step | Duration | What to show |
|---|---|---|
| **1. Problem** | 20s | A real shop story from the interviews — a specific quote from a real owner. |
| **2. Sale** | 30s | Cashier records two sales: one on credit, one with a Mobile Money reference. Receipt prints. Stock drops in real time. |
| **3. Offline** | 40s | Disconnect internet. Make a sale. Reconnect. Show it syncing to the cloud. Show the dashboard updating. |
| **4. Dashboard** | 25s | Owner opens the PWA on a phone. Sees updated sales, low-stock alert, outstanding debts. Receives a low-stock WhatsApp notification. |
| **5. AI agent** | 40s | Customer WhatsApp: "Do you have Nescafé 200g? How much?" → correct answer. Owner WhatsApp: "What sold today?" → accurate summary. |
| **6. Roadmap** | 15s | Staff management, expenses, multi-branch, MoMo API. Architecture scales to it. |

**Backup plan:**
- Pre-recorded video of the full path saved on a USB key.
- Second laptop with identical data loaded and app running.
- If WhatsApp is unavailable: show the n8n flow editor and replay a logged conversation.

---

## 11. Pitch outline

1. The problem — with real quotes and numbers from the interviews
2. Who we built for and what we heard
3. The solution in one picture (architecture diagram)
4. Why we beat existing tools for a Cameroonian shop
5. Live demo *(follow §10)*
6. How it scales — architecture and roadmap
7. Business model: price per shop per month, setup support, first target customers, customer acquisition strategy
8. The team and what each person built
9. Ask / next steps

**Prepare answers for likely jury questions:**
- "Why not use Odoo or Zoho?" → they don't work offline, don't support MoMo, and are too expensive for small shops.
- "What if the AI gives a wrong answer?" → it reads only from the API; it cannot invent data. It states data freshness.
- "How do you handle security and customer data?" → JWT with scoped tokens, no customer data in the AI model, audit log for all sensitive actions.
- "Who pays and how much?" → price per shop per month; estimate based on interview responses.
- "What did you build beyond the class project?" → offline sync architecture, WhatsApp agent, PWA dashboard — explain each.
- "What happens if a team member leaves?" → backup owner system, documented code, AI-assisted knowledge transfer.

---

## 12. Risks and mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Scope too big | High | Nothing polished | MVP list is frozen. New ideas go to Phase 2 — no exceptions. |
| Team lacks a skill | Medium | Delays | Weekly teach-in sessions. One prototype per hard topic in Weeks 1-2. Use AI to generate boilerplate; humans review and understand. |
| Sync bugs / data loss | Medium | Demo failure | Event-sourced design (movements, not overwrites). Stress test 10× per change. Pre-recorded video backup. |
| AI agent gives wrong answer | Low | Credibility loss | Agent reads API only, shows data timestamp, falls back to "I'll check with the owner". |
| **WhatsApp Business API approval delayed** | **High** | **No live agent** | **Start registration in Week 1.** Fallback: n8n flow on a test number or simulated conversation replay. |
| Class project reuse questioned | Medium | Competition penalty | Check rules, inform teacher, document what is new in the project file. |
| Teammate unavailable | Medium | Blocked work | Each area has a named backup owner (§6). |
| Power or internet fails at demo | Medium | Demo failure | Offline-first design, fully charged laptop, video backup on USB. |
| Receipt printer not available at demo | Low | Missing demo step | Test with PDF "print to file" as fallback. Bring a compatible USB thermal printer. |
| PC too slow at demo venue | Low | Poor impression | Test installer on a low-spec PC (4 GB RAM) in Week 9. Optimize queries. |
| n8n self-hosted instance unavailable | Low | No live agent | Pre-configure a cloud-hosted n8n instance as backup. |

---

## 13. Open questions — assign and resolve by end of Week 1

| # | Question | Owner | Deadline | Status |
|---|---|---|---|---|
| 1 | Real submission deadline and competition date | `TO FILL` | Week 1 | ❌ Open |
| 2 | Is the project built live during the competition or presented as pre-built? | `TO FILL` | Week 1 | ❌ Open |
| 3 | Evaluation criteria and weights (technical, business, innovation, demo?) | `TO FILL` | Week 1 | ❌ Open |
| 4 | Rules on reusing coursework — what must be new? | `TO FILL` | Week 1 | ❌ Open |
| 5 | Rules on using AI tools to write code | `TO FILL` | Week 1 | ❌ Open |
| 6 | Is a working prototype required at submission? | `TO FILL` | Week 1 | ❌ Open |
| 7 | **Spring Boot or Javalin?** *(email the teacher)* | Backend dev | End Week 1 | ❌ Open |
| 8 | Final project name | All | End Week 1 | ❌ Open |
| 9 | The 5 team members, their roles and backup assignments | Project lead | End Week 1 | ❌ Open |
| 10 | WhatsApp Business account and test number registered | AI/dashboard dev | End Week 1 | ❌ Open |

---

## 14. Working agreements

- **Weekly meeting (30 min):** what was done, what blocks, what is next. Rotate note-taker.
- **Daily update:** a 5-line message in the group chat when working on a task.
- **Commits:** small, meaningful messages, no direct commits to `main`. All changes via feature branches with a PR.
- **Decisions:** written in **section 16** *(decisions log)* immediately after the meeting. Disagreements are settled by the project lead after hearing everyone.
- **Stuck rule:** ask for help after **45 minutes** stuck — not after 3 days.
- **AI usage:** use AI to generate boilerplate and drafts. You must read, test and understand every line before committing it. The jury interviews *you*, not the model.

---

## 15. Working with AI assistants

**Available tools:** Antigravity IDE (Gemini), Claude, ChatGPT Plus.

**Setup:** create a ChatGPT Project or Claude Project named after the project. Upload this file. Paste the instructions below. Start **one chat per backlog item** — do not mix topics.

### Project instructions *(paste into the AI project)*
```
You are a senior mentor helping a team of 5 Cameroonian software students build a competition project (SJI Innova). Read SJI_INNOVA_PROJECT.md as the source of truth.
Rules:
- Respect the stack and decisions in the file. Do not introduce new tools unless I ask.
- The team knows Java (Swing, JDBC, MySQL) and is learning the rest. Explain simply, in small steps.
- Give complete, runnable code with file names. Mention what to test.
- Think about Cameroonian reality: unreliable internet, power cuts, modest PCs (4 GB RAM, Windows 10), Mobile Money, FCFA (integer amounts, no sub-unit).
- If my request conflicts with the MVP scope, say so before answering.
- If something is unclear, ask one precise question.
```

### Session routine
1. Open a new chat. State which backlog item you are working on (e.g. "M2: sale void flow").
2. Ask the AI to plan in steps, then implement **one step at a time**.
3. Run and test everything yourself. Paste exact error messages back.
4. Ask the AI to explain any code you do not understand **before you commit it**.
5. At the end, update §16 and tick the backlog item.

### Prompt templates

**Plan a task**
```
Backlog item: <ITEM>. Context: <what exists today>. Break it into small steps I can test one by one, with the order and the risk in each step.
```

**Write code**
```
Implement step <N>: <description>. Use Java (Swing/JDBC) in the three-layer structure (UI / service / data). Give full file names, the SQL, and how to test it manually.
```

**Debug**
```
Code: <code>. Exact error: <error>. Expected behaviour: <expected>. Explain the cause first, then the fix.
```

**Code review**
```
Review this code for bugs, security issues, offline/sync problems and readability. List problems by severity (critical / major / minor), then give corrected code.
```

**Learn a concept**
```
Explain <concept> as if I know Java basics but not <topic>. Give a 30-line example I can run, then 3 exercises to practise.
```

**Jury preparation**
```
Act as a tough jury member. Ask me 10 questions about SJI Innova (technical, business, local context) one at a time. After each answer, tell me how to improve it.
```

### AI usage rules
- **Check competition rules on AI tools** before relying on them for submission. `TO FILL`
- Never paste passwords, API keys, or real customer data into any AI tool.
- You must understand and be able to explain every line you submit.
- Verify AI claims about Cameroonian laws, VAT rates, and Mobile Money rules with an official source.
- Use AI to **teach you**, not only to produce code. The jury interviews the team, not the model.

---

## 16. Status log

*Add the newest entry at the top.*

| Date | Who | Done | Next | Blockers |
|---|---|---|---|---|
| 2026-10-20 | *(lead)* | Revised master file (v0.2) | Fill placeholders, confirm deadlines, start interviews | Deadlines unconfirmed |

### Decisions log

| Date | Decision | Reason |
|---|---|---|
| *(date)* | Offline-first with SQLite locally, MySQL in the cloud | Internet and power cuts; easy install |
| *(date)* | Java Swing + FlatLaf for the desktop app | Team already knows Java |
| *(date)* | MVP scope frozen as in §3 | Avoid overbuilding |
| *(date)* | n8n for the AI agent (not Python) | No Python developer assigned; n8n needs no extra language |
| *(date)* | Owner dashboard as PWA (not native app) | Meets the mobile need; offline-capable without a native app |
| 2026-10-20 | `current_stock` snapshot column added to `products` | SUM(movements) too slow for 1000+ products; snapshot cached after each sync |
| 2026-10-20 | `tax_rate` and `discount_amount` added to data model | FCFA VAT and local discounts are core features, not Phase 2 |
| 2026-10-20 | Sales void/return and audit log added to MVP | Without void, a cashier error corrupts stock and revenue permanently |
| 2026-10-20 | `sessions` table added for API JWT management | Auth mechanism was undefined; scoped tokens separate customer and owner access |
