# Health Claim — Case Desk v3 (Spring Boot + MySQL)

Multi-admin claims-investigation desk: Main Admin → State Admins → Office Assistants → Investigators,
with case assignment, investigator checklists, review/send-back, approvals, Excel/PDF export and an audit log.

## Run it

For Windows installation, database setup, build, local launch, and a temporary demo link, see [DEPLOYMENT.txt](./DEPLOYMENT.txt).

First login: **mainadmin** + the bootstrap password. You are forced to set your own password. There is no admin/admin any more.
For production, configure `DB_URL`, `DB_USER`, `DB_PASSWORD`, `APP_CRYPTO_KEY`, and `APP_BOOTSTRAP_ADMIN_PASSWORD` as environment variables; never commit secrets. Back up `APP_CRYPTO_KEY` because encrypted data is unreadable without it. `APP_UPLOAD_DIR` controls where photos/Aadhaar scans are stored — back it up with the DB.


## What's new in v3/v4 — location routing, per-part privacy, response columns, part transfers

**1. Assign each part to an investigator (new-case form).** The form now asks for the *patient district* and the *hospital district*.
- Districts **differ** → an amber hint asks which investigator takes the **Hospital part** and which takes the **Patient part**.
- Districts **same** → one investigator can take both (or you may still split them). There is **no restriction** either way.
- Every part of the claim type (hospital, patient, nominee, witness, police station, location, medical shop) has its own investigator selector, plus a "give ALL parts to one investigator" quick-fill. Investigators in the same district as the hospital/patient are marked "✓ same district".
- Editing a case (Edit details / re-assign parts) can move a part to someone else; that part's earlier answers are cleared.

State and district entries for admins, investigators, patients, hospitals, and (when applicable) police stations use cascading dropdowns. The bundled India location list is from [KTBsomen/Indian-state-district-json](https://github.com/KTBsomen/Indian-state-district-json), sourced from the Government of India directory; its MIT license is included alongside the JSON in `src/main/resources/static`.

**2. Cases list, Excel and PDF now show the part columns.** For the **Hospital part** and **Patient part** separately: *Assigned (Yes/No)*, *Investigator*, *Response (Yes / No / Pending / Yes via another person)* and the *Reason for No*. Other parts are summarised in one column, and pending part transfers are listed. Excel has separate coloured columns with a filter row; the PDF shows one block per part.

**3. Investigators only work on their own parts.** If hospital and patient go to two people, each sees and submits only their own part(s). The case becomes *Submitted* when **all** parts are submitted; until then it shows "In progress (1/2 parts submitted)". "Send back" lets staff tick which parts to re-open.

**4. Route cross-state cases to the right State Admins.** Main Admin chooses a hospital State Admin matching the hospital state. When a patient part is required, Main Admin also chooses a patient State Admin matching the patient state. A same-state button copies the hospital State Admin to the patient selection. Hospital-related and other claim parts route to the hospital State Admin; the patient part routes to the patient State Admin. State Admins and their Office Assistants can manage and review only their routed parts. Each team reviews its own submitted parts; the case is marked completed and visible to Main Admin after all routed teams approve.

Investigators see full details only for their own parts; other investigators' parts show only *Completed* or *Not completed*. In a cross-State case, each State Admin / Office Assistant sees details for their own State Admin's parts and completion-only status for other State Admins' parts. This is enforced in the API response and action authorization, not only in the UI. DOA/DOD remain native date inputs with the browser calendar picker.

**4. Part-transfer request (investigator → admin).** In an open part the investigator clicks *"Cannot do this part? Request transfer…"*, gives a reason and optionally where the person/place is now (e.g. patient's permanent address is Guntur but the patient is in Hyderabad).
- Admins, State Admins, Main Admin and Office Assistants get a **"Part Transfers" tab with a red badge, a yellow alert bar on every page, and a toast** (checked every 60 s).
- Staff can **give the part to another investigator**, **deny** it (reason shown to the investigator), or — if the person is in **another state** — **forward it to the Main Admin**, who can pick any investigator in any state.
- The investigator can cancel a pending request. A part with a pending request cannot be submitted.
- Every request/decision is in the audit log; the part keeps a "Transferred from A to B on date by X" history.

**Upgrade note:** new columns (`cases.hospitalDistrict`, `case_parts.investigatorUsername`, `submitted`, transfer fields …) are added automatically by Hibernate (`ddl-auto=update`). Cases created with v2 keep working (their parts fall back to the case's investigator); their hospital district is blank until someone edits the case.

## Who can do what

| | Main Admin | State Admin | Office Assistant | Investigator |
|---|:-:|:-:|:-:|:-:|
| Sees data of | everyone | own state/team only | own admin's team only | own cases only |
| Create State Admin | ✔ | | | |
| Create Office Assistant | ✔ | ✔ | | |
| Create Investigator | ✔ (approved at once) | ✔ (approved at once) | ✔ → **goes to admin for approval** | |
| Approve / send back investigator | ✔ | ✔ | | |
| Assign new case, edit assignment | ✔ | ✔ | ✔ | |
| Cases list, filters, Excel/PDF (with hospital/patient part columns) | ✔ | ✔ | ✔ | |
| Send case back (any time, no request needed) | ✔ | ✔ | ✔ | |
| Review and approve own submitted parts; send completed case to Main Admin | ✔ | ✔ | ✔ | |
| Complete case directly / approve-deny edit requests | ✔ | ✔ | ✔ | |
| View investigator Aadhaar upload (within scope) | ✔ | ✔ | ✔ | Own account only |
| View another user's password | ✗ | ✗ | ✗ | ✗ |
| Reset another user's password (requires change at next login) | ✔ | ✔ | ✗ | ✗ |
| Audit log | ✔ | | | |
| Fill checklist, submit, letters, forms (own parts) | | | | ✔ |
| Request part transfer | | | | ✔ |
| Decide part transfer (re-assign / deny) | ✔ | ✔ | ✔ | |
| Decide cross-state transfer forwarded to Main Admin | ✔ | | | |

Menus — Admin: New Case · Cases List · Investigators · Request for Edit · Part Transfers · Create Investigator (+ Office Assistants, + State Admins and Audit Log for Main Admin).
Investigator: Case List · Withdrawal Letter · Fraud Letter · Employee Info Form · Patient Questionnaire.

Isolation: users carry an `ownerAdmin`; each case part also has an owner State Admin. State Admin A never sees B's investigators or assistants and only sees the case parts routed to A (with completion-only status for other State Admin parts). Main Admin sees all.

## Investigator Aadhaar uploads and missing-file 404

Admins and office assistants can open an investigator's Aadhaar upload only when that investigator is within their permitted scope. Investigators can access only their own upload. Uploads are served through an authenticated endpoint, not directly from the public static folder.

If a user's record points to an upload that is missing from `APP_UPLOAD_DIR`, opening `/api/users/{username}/aadhar-file` in a browser returns HTTP 404 with the branded [Aadhaar file not found page](./src/main/resources/static/aadhar-not-found.html). API clients continue to receive a JSON 404 response. The page explains that an administrator must restore the file from backup or upload it again; it does not expose or recreate the missing document.

## The case flow

1. Admin/assistant assigns: TMCID, patient, district + village, DOA, DOD, hospital, insurance, claim type, investigator.
2. The claim type decides what the investigator must handle (edit `model/ClaimType.java` to change):
   Cashless → hospital · Reimbursement → hospital + patient · Personal Accident → nominee, witness, police station, hospital ·
   Medical Bill Verification → hospital **or** local medical shop (chosen when assigning) · Reimbursement DNDM → hospital ·
   Death → hospital, nominee, police station, location.
3. Investigator opens the case and answers **Yes/No** for every part. **No** → reason required.
   **Yes** + "collected through another person" → remarks required (who sends it, when). Submit is refused until every part is answered.
4. Any remarks / "No" / via-another-person puts the submitted case in **"Submitted with remarks"** for review; clean ones appear as **"Submitted"**.
5. State Admin/assistant reviews only their own submitted parts. For cross-state cases, the case is completed and sent to Main Admin after every routed team approves. For flagged parts, reviewers must confirm the documents are received. They can instead **Send back** selected parts with a reason — the investigator sees a red alert. Main Admin can review and complete a fully submitted case directly.
6. A completed case is locked; the investigator can request an edit, an admin approves (→ sent back) or denies.

## Security — what is in place

- Spring Security sessions: HttpOnly + Secure + SameSite=Strict cookie, 30-min timeout, new session id on login, CSRF token on every write.
- Passwords BCrypt (cost 12). Account locks 15 min after 5 wrong attempts; per-IP login throttle; generic errors; timing-safe unknown-user check.
- Deactivating a user ends their session on their next request. First-login forced password change for admins/assistants and after resets.
- Aadhaar/PAN/DL/bank fields are **AES-256-GCM encrypted in the database**.
- Uploads: type verified by file signature (JPG/PNG/PDF), size-capped, random names, stored outside the web root, served only through an authenticated, scope-checked endpoint.
- Strict CSP (no inline scripts), no framing, no-referrer, HSTS, `nosniff`. Excel export neutralises formula injection.
- Audit log: logins/failures, lockouts, password resets, approvals, case actions, exports.
- Passwords are stored as BCrypt hashes only; admins cannot view another user's password. An admin reset requires that user
  to choose a new password at their next login.

## Still recommended before real use
- Run behind HTTPS (Nginx/Caddy + Let's Encrypt). Do not expose port 8080 directly.
- Back up the database, the `uploads/` folder and `APP_CRYPTO_KEY` together; test a restore.
- Switch `spring.jpa.hibernate.ddl-auto` to `validate` + Flyway once the schema settles.
- Consider 2-factor login for admins, and moving login throttling to a shared store if you run more than one instance.
