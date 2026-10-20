# SJI INNOVA — PROJECT MASTER FILE

> **Single source of truth** for the team and for any AI assistant.
> Keep it short, current and honest. If a decision changes, change it here first.
> **Document version:** v0.3 — 2026-10-20 (supersedes v0.2)

**Project working name:** `TO FILL` *(e.g. "Innova", "ShopMate", "BizLocal")*
**One-line pitch:** An inventory, sales and customer-credit system for Cameroonian SMEs that keeps working offline and comes with a WhatsApp AI assistant that answers from live stock data.
**Submission deadline:** `TO FILL` **Competition date:** `TO FILL`
**Last updated:** 2026-10-20 **Maintained by:** `TO FILL` *(project lead)*

### What changed in v0.3
- Sync redesigned as **append-only events** (v0.2 could not sync status changes such as voids or confirmed MoMo payments)
- Local stock updated **immediately**, not only after sync
- WhatsApp **owner identity check** (phone allowlist + PIN); debt reminders become owner-approved
- **VAT optional** per shop; quantities stored as integers in thousandths (kg, litres)
- MVP split into **Must / Should / Could** tiers with a **feature freeze at end of Week 8**
- Minimal sync endpoint moved earlier; **workload rebalanced** (shared core library)
- Added **automatic local backup** and a **real pilot shop**

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
- **FCFA, optional VAT, French/English** from day one
- **WhatsApp AI assistant** that answers customers and the owner from real stock data
- Simple and affordable for a small shop

**Why we win.** Not "more features than Odoo" but "the only one that understands a Cameroonian shop", plus a live, reliable demo.

**Positioning rule.** Target = small and medium businesses (shops, pharmacies, wholesalers, restaurants). Larger companies are a roadmap item shown through scalable architecture, not claimed today.

---

## 2. Users and problems *(validate with interviews)*

| User | Interface | Main need |
|---|---|---|
| Cashier | Desktop app | Fast sales, receipts, works offline |
| Owner / manager | Owner dashboard (mobile-friendly web, installable as PWA) | Stock levels, sales, debts, alerts on phone |
| Customer | WhatsApp | "Do you have X? What's the price?" |

> **Mobile:** a native mobile app is out of scope. The owner dashboard is a **PWA**: a web page installed on the phone that caches data so it opens after the first visit even without internet.

**Interview plan (Weeks 1-2):** each team member interviews 2 shop owners (~10 total). Questions:
- What costs you the most today: stock-outs, theft, unpaid debts, not knowing your profit?
- How often do internet and power fail? What do you do then?
- How do you record sales and debts now? What device do you use?
- How do customers contact you (phone, WhatsApp)? How do they pay (cash, MoMo, credit)?
- Do you charge VAT, and who does your accounting?
- Would you pay for a tool that solves the top problem? How much per month?
- **Would you let us test the app in your shop for one afternoon?** (pilot shop, see §8)

Record results in `/research/interviews.md` (one block per interview) and keep the top 3 pains here:

1. *(fill after interviews)*
2.
3.

---

## 3. Scope (tiered)

**Rule:** build all **Must** items first. **Should** items start only when the Must milestone is green. **Could** items only if everything else is done. **Feature freeze at the end of Week 8**: nothing new in Week 9. When behind schedule, cut from the bottom (Could, then Should).

### MUST — the demo cannot work without these
- [ ] Products, categories, stock levels, low-stock alerts (units: piece / kg / litre)
- [ ] Sales: cash, Mobile Money reference, on credit; receipt (PDF/print)
- [ ] Sales void with reason and audit trail
- [ ] Customers, debts and repayments
- [ ] Users and roles (owner, cashier) with login
- [ ] Offline mode on desktop + background sync to the cloud (append-only events)
- [ ] Automatic local backup + export to USB
- [ ] Owner dashboard (PWA): daily/weekly sales, top products, low stock, outstanding debts
- [ ] WhatsApp AI agent, **customer mode**: availability and price
- [ ] French and English interface (all strings externalized)

### SHOULD — only after the Must milestone is green
- [ ] WhatsApp agent **owner mode** (sales summary, low-stock list) with phone allowlist + PIN
- [ ] Daily cash reconciliation (expected vs counted cash)
- [ ] Discount per sale line
- [ ] Barcode/SKU scanner (USB keyboard mode: cheap to add)
- [ ] CSV bulk import of products (migration from Excel)
- [ ] Low-stock WhatsApp alert to the owner

### COULD — only if everything else is finished
- [ ] Printable debt statement
- [ ] ESC/POS thermal printer support (PDF receipt is the baseline)
- [ ] Owner-approved debt reminder messages to customers (see §4, WhatsApp rules)

### Phase 2 — roadmap slide, not built
Staff management, light finance (expenses, P&L), multi-branch, supplier orders, Mobile Money webhook for automatic reconciliation, advanced reports.

### Phase 3 — vision
Full Mobile Money API integration, larger companies, accounting/payroll compliance.

### Explicitly out of scope
Full payroll, tax declarations, large-enterprise ERP features, native mobile app.

---

## 4. Architecture and stack *(decisions)*

**Core principle:** the shop works offline; the cloud and the AI work online.

| Layer | Choice | Notes |
|---|---|---|
| Shared core library | **Java**: data layer (JDBC) + services + sync client | Used by the desktop app **and** the cloud API, written once |
| Desktop app | **Java Swing + FlatLaf** | Cashier interface |
| Local DB | **SQLite** via JDBC | Embedded, safe after power cuts |
| Cloud API | **`TO FILL`**: Spring Boot **or** Javalin | Decide by end of Week 1 — email the teacher. Javalin if Spring Boot was not covered. |
| Cloud DB | **MySQL** | Known by the team |
| Owner dashboard | **HTML + CSS + Chart.js (bundled locally, not from a CDN)** as a **PWA** | Static files served by the API or a free static host |
| AI agent | **n8n** | Calls the cloud API only — never invents data |
| Receipts | **PDF baseline**; ESC/POS thermal later (Could) | Avoid hardware dependence in the demo |
| Passwords | **bcrypt or Argon2** hashes | Never store plain text |
| Packaging | `jpackage` installer | Bundles Java runtime |

**Code layers (mandatory):**
1. **UI (Swing)** — displays data and collects input, no business logic
2. **Services** — all business rules (sell, void, receive stock, record debt, compute tax)
3. **Data layer (JDBC)** — the only code touching the database

### Sync rules (v0.3)

**Two kinds of data, two rules:**

**A. Transactional data = append-only events (never edited).** Stock movements, sales, sale items, **sale events** (created, MoMo confirmed, paid in full, voided), debt payments and audit entries are only ever **inserted**. A change of state is a **new event**, never an update of an old row.
- Every record has a locally generated **UUID** and a `device_id`; the server ignores UUIDs it already has (safe retries). Because events are never edited, nothing is lost by this rule.
- A sale's current `status` is **derived from its events** and cached locally; the cache is never synced as an update.
- **Voiding a sale** = a `voided` sale event + compensating stock movements (type `void`). The original sale stays untouched.
- **Stock:** `current_stock_milli` on the product is a **local cache updated in the same database transaction** as every stock movement, so the cashier always sees correct stock offline. The cloud recomputes it as `SUM(movements)`.
- **Conflicts:** if two devices sold the last unit, both sales are kept; stock goes negative with a visible flag and the owner is notified (dashboard first, WhatsApp as a Should item). Never hidden.

**B. Catalog data (product names, prices, customers' names) = last write wins.** These rows carry `updated_at` and `version`; the newest write wins; the owner has authority.

**Crash safety:** local writes are immediate (SQLite WAL mode), a sale is complete or cancelled, never half-saved. Sync runs in a background thread when internet is available, with retry.

**Backup:** automatic local database copy at a fixed interval (e.g. at closing time) to a backup folder, plus a one-click export to USB. Restore tested at least once before the demo.

### Quantities, money and tax
- **Money:** integers in FCFA (no sub-unit).
- **Quantities:** integers in **thousandths** (1.5 kg = 1500, 1 piece = 1000) to avoid rounding errors; the UI shows normal decimals.
- **VAT:** optional per shop (`vat_enabled`). Many small businesses may be under a simplified tax regime — **verify with an accountant** before claiming rates. When enabled, `tax_rate` is stored on each sale line at the time of sale and never recalculated.

### AI agent and WhatsApp rules
- Reads through defined API endpoints only — no invented prices or stock.
- **Two access levels with separate API tokens** held by the n8n flows: *customer* (availability and price only) and *owner* (summaries, low stock, debts). Cost prices and margins are never exposed to the customer token.
- **Owner identity:** owner mode only responds to **phone numbers in the `authorized_phones` allowlist**, and sensitive actions need a **PIN**. Unknown numbers always get customer mode.
- **Debt reminders to customers:** WhatsApp only allows business-initiated messages through **pre-approved templates** (approval time and possible cost), and customers should have agreed to be contacted. So reminders are **owner-triggered**: the system prepares the message, the owner approves it. Automatic reminders stay in Phase 2.
- If data is stale (shop offline): the answer states "stock as of HH:MM" and offers to notify the owner.
- If the agent cannot answer: "I'll check with the owner" and the owner is notified.

---

## 5. Data model *(starting point — refine with the team)*

| Table | Key fields |
|---|---|
| `shops` | `id` (UUID), `name`, `currency` (FCFA), `language`, `vat_enabled` (default false), `vat_rate` (used only if enabled) |
| `users` | `id`, `shop_id`, `name`, `role` (owner / cashier), `password_hash`, `created_at` |
| `authorized_phones` | `id`, `shop_id`, `phone`, `role`, `pin_hash`, `created_at` *(WhatsApp owner-mode allowlist)* |
| `sessions` | `id`, `user_id`, `token_hash`, `expires_at`, `created_at` |
| `products` | `id`, `shop_id`, `name`, `sku_barcode`, `category`, `unit` (piece / kg / litre), `unit_price`, `cost_price`, `tax_rate`, `min_stock_milli`, `current_stock_milli` *(local cache, updated in the same transaction as each movement)*, `updated_at`, `version` |
| `stock_movements` | `id`, `product_id`, `type` (sale / delivery / adjustment / void / return), `quantity_milli` (+/-), `ref_id` (sale or event), `device_id`, `created_at`, `synced` |
| `sales` | `id`, `shop_id`, `user_id`, `customer_id` (nullable), `subtotal`, `tax_amount`, `discount_total`, `total`, `payment_type` (cash / momo / credit), `momo_reference`, `status` *(derived cache from sale_events)*, `device_id`, `created_at`, `synced` |
| `sale_items` | `id`, `sale_id`, `shop_id`, `product_id`, `quantity_milli`, `unit_price`, `tax_rate`, `discount_amount` |
| `sale_events` | `id`, `sale_id`, `shop_id`, `type` (created / momo_confirmed / paid_in_full / voided), `user_id`, `reason`, `device_id`, `created_at`, `synced` |
| `customers` | `id`, `shop_id`, `name`, `phone`, `created_at`, `updated_at`, `version` |
| `debt_payments` | `id`, `customer_id`, `sale_id` (nullable), `amount`, `method`, `device_id`, `created_at`, `synced` |
| `devices` | `id`, `shop_id`, `name`, `last_sync_at` |
| `audit_log` | `id`, `shop_id`, `user_id`, `action` (e.g. `VOID_SALE`, `EDIT_PRICE`), `target_table`, `target_id`, `old_value` (JSON), `new_value` (JSON), `created_at`, `synced` |
| `cash_reconciliations` *(Should)* | `id`, `shop_id`, `user_id`, `session_date`, `expected_cash`, `actual_cash`, `difference`, `notes`, `created_at` |

**Global rules:** UUID primary keys, no auto-increment; `created_at` everywhere; integers for money and quantities; transactional tables are insert-only.

---

## 6. Team and roles *(5 people)*

| Role | Owner | Responsibilities | Backup for |
|---|---|---|---|
| Project lead & integration | `TO FILL` | Planning, this file, merges, submission, customers/debts/void screens with the desktop dev | Backend |
| **Backend & shared core** | `TO FILL` | Data layer, services, sync client + server endpoints, cloud API, auth | Desktop |
| **Desktop UI** | `TO FILL` | Swing screens, i18n, receipts, backup/export, installer | Backend |
| AI agent & dashboard | `TO FILL` | Mockups (early), n8n flows, PWA dashboard, WhatsApp setup | Research/QA |
| Research, QA & pitch | `TO FILL` | Interviews, competitor table, pilot shop, testing the demo path, slides | AI agent |

**Why this split:** the backend dev writes the shared core library once; the desktop and the cloud API both use it. The desktop dev builds screens on top, so nobody is a bottleneck. Everyone must understand the full end-to-end flow before the demo.

---

## 7. Timeline *(9 weeks)*

> AI tools (Antigravity IDE, Claude, ChatGPT) speed up boilerplate and drafts. They do **not** replace interviews, testing or integration. Do not assume a fixed time saving.

**Idea submission deadline:** `TO FILL` **Competition date:** `TO FILL`

> **If the competition requires building live** (open question #2), this timeline becomes a **preparation plan**: build the shared core, the sync pattern and an API template, then practice timed feature builds. Answer question #2 in Week 1.

### Week 1 — Lock & setup
| Goal | Output |
|---|---|
| Fill all `TO FILL` items; answer open questions 1-6 with the organizers | Completed file |
| Decide Spring Boot vs Javalin (email the teacher) | Logged in §16 |
| Git repo, branch rules, same Java version everywhere | Working repo |
| Register WhatsApp Business test number (approval takes time) | Request submitted |
| Start interviews (5 this week) | 5 notes |
| **AI task:** generate DDL (SQLite + MySQL) from §5 | Reviewed schema |

### Week 2 — Research + foundations
| Goal | Output |
|---|---|
| Finish 10 interviews; top 3 pains; competitor table | Problem statement with real quotes |
| Backend: DAO + service skeletons, unit tests, **event-sync design** | Tested core |
| Desktop: Swing + FlatLaf prototype of the sale screen | Prototype 1 |
| AI/dashboard: clickable mockups + n8n prototype "do you have X?" | Mockups, Prototype 4 |
| Lead: submission outline | Outline |

### Week 3 — Must: products, stock, sales
| Goal | Output |
|---|---|
| Products, stock list, sale flow (cash, MoMo ref, credit), PDF receipt | Sale works offline |
| Core: sale + stock-movement services, local stock cache in same transaction | Unit tests green |
| i18n skeleton (FR/EN) | Language switch |
| AI/dashboard: PWA skeleton with fake data | Layout ready |
| Lead: customers + debts screens | Debt flow started |

### Week 4 — Must: void, login, debts + submission
| Goal | Output |
|---|---|
| Void via sale events + compensating movements + audit log | Void tested |
| Login and roles; debt repayments | Roles enforced |
| **Minimal sync endpoint** on the server (accept events, ignore known UUIDs) | Postman test passes |
| Write and review the submission (real quotes from interviews) | Submitted ✅ |
| **Milestone M2:** full Must desktop flow works offline | ✅ |

### Week 5 — Offline sync
| Goal | Output |
|---|---|
| Background sync client with retry, idempotent upload | Runs when online |
| Sale events (MoMo confirmed, voided) sync correctly | Status changes arrive in cloud |
| Conflict flag for negative stock | Visible to owner |
| Automatic local backup + USB export | Restore tested |
| **Stress test:** 20 sales offline, reconnect, 10 cycles, zero loss | ✅ |

### Week 6 — Cloud API + security
| Goal | Output |
|---|---|
| REST endpoints: products, stock, sales summary, debts, availability | JSON in Postman |
| Token auth: owner vs customer scopes; session expiry | Permission tests pass |
| `authorized_phones` allowlist + PIN check endpoint | Tested |
| **Milestone M4:** customer token cannot reach owner data | ✅ |

### Week 7 — Dashboard (PWA) + first Should items
| Goal | Output |
|---|---|
| Dashboard on real data: sales chart, top products, low stock, debts | Works on a phone |
| Manifest + service worker; Chart.js bundled locally | Opens offline after first visit |
| Test on a real Android phone on slow data | Loads in < 4 s |
| Should items (only if Must is green): scanner, discounts, CSV import | Tiered by priority |

### Week 8 — WhatsApp agent + pilot shop + FEATURE FREEZE
| Goal | Output |
|---|---|
| n8n customer flow: availability and price | Correct answers |
| Owner flow (allowlist + PIN): sales summary, low stock | Works for owner only |
| Stale-data message and "I'll check with the owner" fallback | Tested |
| **Pilot:** one real shop tests the app for an afternoon; collect feedback | Quotes and bug list |
| **Feature freeze at end of week** | No new features after this |

### Week 9 — Hardening, buffer and demo
| Goal | Output |
|---|---|
| Installer tested on 3 Windows PCs (4 GB RAM) | Runs on clean PCs |
| FR/EN strings 100% complete | QA sign-off |
| Fix pilot bugs; use remaining time as **buffer** | Stable build |
| Slides final; demo rehearsed 5 times, < 3 min | Deck + timings |
| Backup video on USB; second laptop with identical data | Ready |
| **Milestone M6:** 5 clean runs in a row, online and offline | ✅ |

### Build order
1. Shared core + desktop: products, sales, stock (SQLite)
2. Void, debts, login
3. Minimal sync endpoint → full sync
4. Cloud API + security
5. Dashboard (PWA)
6. AI agent (n8n)
7. Pilot, installer, polish

---

## 8. Backlog *(tick items, move to §16 when done)*

### M0: Setup (Week 1)
- [ ] Git repo; `main` always stable; one branch per feature; reviewed PRs `[lead]`
- [ ] Shared folder, this file, interview notes `[lead]`
- [ ] Chat group + fixed 30-min weekly meeting `[lead]`
- [ ] Same Java version and IDE for everyone `[all]`
- [ ] Spring Boot vs Javalin decision logged `[lead + backend]`
- [ ] Register WhatsApp Business test number `[AI/dashboard]`

### M1: Research and submission (Weeks 1-4)
- [ ] 10 interviews summarized `[research]`
- [ ] Competitor table `[research]`
- [ ] Mockups: sale, stock, debts, dashboard, WhatsApp chat `[AI/dashboard]`
- [ ] Prototype 1: Swing sale screen `[desktop]`
- [ ] Prototype 2: sale + movement saved in SQLite `[backend]`
- [ ] Prototype 3: sale event uploaded to a minimal API `[backend]`
- [ ] Prototype 4: n8n answers "do you have X?" `[AI/dashboard]`
- [ ] Submission written, reviewed by 2 outsiders `[lead + research]`
- [ ] Pilot shop identified `[research]`

### M2: Must desktop (Weeks 3-4)
- [ ] Shared core: DAOs, services, unit tests `[backend]`
- [ ] Products + stock list with low-stock highlight `[desktop]`
- [ ] Sale flow: cart, cash / MoMo reference / credit, PDF receipt `[desktop]`
- [ ] Local stock cache updated in the same transaction `[backend]`
- [ ] Void via events + compensating movements + audit log `[backend + desktop]`
- [ ] Customers, debts, repayments `[lead]`
- [ ] Login and roles `[desktop]`
- [ ] i18n FR/EN `[desktop]`

### M3: Offline and sync (Weeks 4-5)
- [ ] Minimal sync endpoint (Week 4) `[backend]`
- [ ] Sync client: retry, idempotency, all event types `[backend]`
- [ ] Conflict flag for negative stock `[backend]`
- [ ] Automatic backup + USB export + restore test `[desktop]`
- [ ] Stress test: 20 offline sales, 10 reconnect cycles `[research/QA]`

### M4: Cloud API and security (Week 6)
- [ ] REST endpoints (products, stock, sales summary, debts, availability) `[backend]`
- [ ] Owner vs customer tokens; session expiry `[backend]`
- [ ] `authorized_phones` + PIN verification `[backend]`
- [ ] Permission tests (customer cannot reach owner data) `[QA]`

### M5: Dashboard and agent (Weeks 7-8)
- [ ] PWA dashboard on real data, offline cache `[AI/dashboard]`
- [ ] n8n customer flow `[AI/dashboard]`
- [ ] n8n owner flow with allowlist + PIN *(Should)* `[AI/dashboard]`
- [ ] Stale-data message and fallback to owner `[AI/dashboard]`
- [ ] Should items by priority: scanner, discounts, CSV import, cash reconciliation, low-stock WhatsApp alert `[desktop/lead]`
- [ ] Pilot shop test and bug list `[research/QA]`
- [ ] **Feature freeze (end of Week 8)** `[lead]`

### M6: Polish and delivery (Week 9)
- [ ] Installer tested on 3 clean Windows PCs `[desktop]`
- [ ] FR/EN strings complete `[all + QA]`
- [ ] Pilot bugs fixed `[all]`
- [ ] Demo rehearsed 5 times; video + second laptop ready `[all]`
- [ ] Slides and one-page summary `[research/QA]`

---

## 9. Quality gates — definition of done

A task is **done** only if:
1. It runs on a **clean Windows machine** (4 GB RAM, Windows 10/11) from the repo instructions alone.
2. It was **tested by someone other than its author**.
3. The author can **explain the code** without reading it.
4. **No secrets** (passwords, API keys, tokens) are committed.
5. It is **merged to `main`** and noted in §16.
6. For sync features: tested with the network **disconnected and reconnected at least 5 times**, including state changes (void, MoMo confirmation).

---

## 10. Demo script *(target: 3 minutes)*

| Step | Time | What to show |
|---|---|---|
| **1. Problem** | 20s | A real quote from an interviewed shop owner |
| **2. Sale** | 30s | Two sales: one on credit, one with a Mobile Money reference; receipt; stock drops |
| **3. Offline** | 40s | Disconnect, sell, void one sale, reconnect; show everything syncing |
| **4. Dashboard** | 25s | Owner opens the PWA on a phone: sales, low stock, debts |
| **5. AI agent** | 40s | Customer asks "Do you have Nescafé 200g? How much?" → correct answer. Owner (allowlisted number) asks "What sold today?" → correct summary |
| **6. Roadmap** | 15s | Staff, finance, multi-branch, MoMo API |

**Backup plan:** pre-recorded video on a USB key; second laptop with identical data; if WhatsApp is unavailable, show the n8n flow and replay a logged conversation.

---

## 11. Pitch outline

1. The problem — real quotes and numbers from interviews
2. Who we built for and what we heard (and what the pilot shop said)
3. The solution in one picture (architecture diagram)
4. Why we beat existing tools for a Cameroonian shop
5. Live demo *(follow §10)*
6. How it scales — architecture and roadmap
7. Business model: price per shop per month, setup support, first target customers, acquisition strategy
8. The team and what each person built
9. Ask / next steps

**Likely jury questions:**
- "Why not Odoo or Zoho?" → they assume stable internet, don't handle MoMo and credit sales the local way, and are costly for small shops.
- "What if the AI is wrong?" → it reads only from the API, shows data freshness, and hands over to the owner.
- "Security and customer data?" → scoped tokens, owner identified by allowlisted phone + PIN, audit log, hashed passwords.
- "Who pays and how much?" → price per shop per month, based on interview answers.
- "What is new beyond the class project?" → offline event sync, WhatsApp agent, PWA dashboard, backup, pilot results.
- "What if the internet or power fails?" → local database, crash-safe writes, backups.
- "What if a teammate leaves?" → backup owners, documented code.

---

## 12. Risks and mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Scope too big | High | Nothing polished | Must/Should/Could tiers; cut from the bottom; feature freeze end of Week 8 |
| Team lacks a skill | Medium | Delays | Weekly teach-ins; one prototype per hard topic in Weeks 1-2 |
| Sync bugs / data loss | Medium | Demo failure | Append-only events; stress test 10×; state-change tests; video backup |
| Stock shown wrong offline | Medium | Wrong sales | Local cache updated in the same transaction as each movement |
| AI gives wrong answer | Low | Credibility loss | API-only reads, freshness timestamp, owner fallback |
| Someone impersonates the owner on WhatsApp | Medium | Data leak | Phone allowlist + PIN; unknown numbers get customer mode only |
| **WhatsApp approval/template limits** | **High** | **No live agent** | Register in Week 1; owner-approved reminders only; fallback: simulated conversation replay |
| Class project reuse questioned | Medium | Penalty | Check rules, inform teacher, document what is new |
| Teammate unavailable | Medium | Blocked work | Named backups (§6) |
| One person overloaded | Medium | Bottleneck | Shared core split (§6); weekly workload check |
| Power/internet fails at demo | Medium | Demo failure | Offline-first, charged laptop, video backup |
| Printer unavailable | Low | Missing step | PDF receipt is the baseline |
| Slow PC at venue | Low | Poor impression | Test installer on a 4 GB RAM PC in Week 9 |
| Wrong tax claim | Low | Credibility | VAT optional; verify with an accountant before the pitch |
| n8n unavailable at demo | Low | No live agent | Backup cloud-hosted n8n instance |
| No real-user proof | Medium | Weak pitch | Pilot shop in Week 8 |

---

## 13. Open questions — assign and resolve by end of Week 1

| # | Question | Owner | Deadline | Status |
|---|---|---|---|---|
| 1 | Real submission deadline and competition date | `TO FILL` | Week 1 | ❌ Open |
| 2 | Is the project built live during the competition or presented pre-built? | `TO FILL` | Week 1 | ❌ Open |
| 3 | Evaluation criteria and weights | `TO FILL` | Week 1 | ❌ Open |
| 4 | Rules on reusing coursework | `TO FILL` | Week 1 | ❌ Open |
| 5 | Rules on using AI tools to write code | `TO FILL` | Week 1 | ❌ Open |
| 6 | Is a working prototype required at submission? | `TO FILL` | Week 1 | ❌ Open |
| 7 | Spring Boot or Javalin? *(email the teacher)* | Backend dev | End Week 1 | ❌ Open |
| 8 | Final project name | All | End Week 1 | ❌ Open |
| 9 | The 5 team members, roles and backups | Project lead | End Week 1 | ❌ Open |
| 10 | WhatsApp Business test number registered | AI/dashboard dev | End Week 1 | ❌ Open |
| 11 | Pilot shop identified and agreed | Research/QA | End Week 4 | ❌ Open |
| 12 | VAT and simplified-regime rules confirmed with an accountant | Research/QA | End Week 4 | ❌ Open |

---

## 14. Working agreements

- **Weekly meeting (30 min):** done, blockers, next. Rotate note-taker. Include a 2-minute workload check.
- **Daily update:** 5-line message in the group chat when working on a task.
- **Commits:** small, meaningful messages; no direct commits to `main`; feature branches with a PR.
- **Decisions:** written in §16 right after the meeting. The project lead settles disagreements after hearing everyone.
- **Stuck rule:** ask for help after **45 minutes**, not after 3 days.
- **AI usage:** use AI for boilerplate and drafts. Read, test and understand every line before committing. The jury interviews you, not the model.

---

## 15. Working with AI assistants

**Available tools:** Antigravity IDE (Gemini), Claude, ChatGPT Plus.

**Setup:** create a ChatGPT Project or Claude Project named after the project. Upload the **latest version of this file** (re-upload after every change). Paste the instructions below. Start **one chat per backlog item**.

### Project instructions *(paste into the AI project)*
```
You are a senior mentor helping a team of 5 Cameroonian software students build a competition project (SJI Innova). Read the uploaded project master file as the source of truth.
Rules:
- Respect the stack and decisions in the file. Do not introduce new tools unless I ask.
- The team knows Java (Swing, JDBC, MySQL) and is learning the rest. Explain simply, in small steps.
- Give complete, runnable code with file names. Mention what to test.
- Respect the sync rules: transactional data is append-only events, state changes are new events, stock cache is updated in the same local transaction.
- Think about Cameroonian reality: unreliable internet, power cuts, modest PCs (4 GB RAM, Windows 10), Mobile Money, FCFA (integer amounts), quantities as integers in thousandths.
- If my request conflicts with the MVP tiers, say so before answering.
- If something is unclear, ask one precise question.
```

### Session routine
1. New chat; state the backlog item (e.g. "M2: void via events").
2. Ask for a plan in steps, then implement **one step at a time**.
3. Run and test everything yourself. Paste exact errors back.
4. Ask the AI to explain any code you do not understand **before committing**.
5. Update §16 and tick the backlog item.

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
Code: <code>. Exact error: <e>. Expected behaviour: <expected>. Explain the cause first, then the fix.
```

**Code review**
```
Review this code for bugs, security issues, offline/sync problems and readability. List problems by severity (critical / major / minor), then give corrected code.
```

**Learn a concept**
```
Explain <concept> as if I know Java basics but not <topic>. Give a 30-line example I can run, then 3 exercises.
```

**Jury preparation**
```
Act as a tough jury member. Ask me 10 questions about SJI Innova (technical, business, local context) one at a time. After each answer, tell me how to improve it.
```

### AI usage rules
- **Check the competition rules on AI tools** before relying on them. `TO FILL`
- Never paste passwords, API keys or real customer data into any AI tool.
- You must understand and be able to explain every line you submit.
- Verify AI claims about Cameroonian laws, VAT and Mobile Money with an official source.
- Use AI to **teach you**, not only to produce code.

---

## 16. Status log

*Add the newest entry at the top.*

| Date | Who | Done | Next | Blockers |
|---|---|---|---|---|
| 2026-10-20 | *(lead)* | Master file v0.3 (event sync, tiers, rebalanced roles) | Fill placeholders, confirm deadlines, start interviews | Deadlines unconfirmed |

### Decisions log

| Date | Decision | Reason |
|---|---|---|
| *(date)* | Offline-first: SQLite locally, MySQL in the cloud | Internet and power cuts; easy install |
| *(date)* | Java Swing + FlatLaf for the desktop app | Team already knows Java |
| *(date)* | n8n for the AI agent | No Python developer; no extra language |
| *(date)* | Owner dashboard as PWA, not native app | Mobile need without a separate app |
| 2026-10-20 | Transactional data is append-only events; state changes are new events | v0.2 rule "ignore known UUIDs" would lose updates (void, MoMo confirmation) |
| 2026-10-20 | Local `current_stock_milli` updated in the same transaction as each movement | Offline cashier must see correct stock |
| 2026-10-20 | WhatsApp owner mode requires allowlisted phone + PIN | Prevent owner impersonation |
| 2026-10-20 | Debt reminders are owner-approved, not automatic | WhatsApp template rules and customer consent |
| 2026-10-20 | VAT optional per shop; quantities as integers in thousandths | Simplified tax regimes; kg/litre sales; no rounding errors |
| 2026-10-20 | MVP tiers (Must/Should/Could) and feature freeze at end of Week 8 | Control scope; keep a buffer week |
| 2026-10-20 | Minimal sync endpoint moved to Week 4 | Sync in Week 5 depends on it |
| 2026-10-20 | Shared core library owned by backend dev | Remove desktop bottleneck; reuse in API |
| 2026-10-20 | Automatic local backup and one real pilot shop added | Owner trust; real-user evidence for the jury |
| 2026-10-20 | PDF receipt is baseline; ESC/POS is a Could item | Avoid hardware risk in the demo |
