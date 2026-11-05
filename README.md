# ResQAI Backend

Spring Boot backend for **ResQAI — AI-Based Smart Ambulance Alert and Dispatch System**.
Provides JWT authentication, emergency profiles, emergency contacts, incident persistence with
idempotent sync, bystander QR reporting, and emergency-assessment metadata storage.

- **Java 21** · **Spring Boot 3.4.5** · **MongoDB** · **Maven**
- No LLM, no cloud AI, no Twilio-by-default. All external providers are optional and must be
  explicitly configured (see section 6).

---

## 1. Architecture

```
Android app (Kotlin)
        │  HTTPS + Bearer JWT
        ▼
Spring Boot REST API  ──►  MongoDB (users, profiles, contacts, incidents, reports, assessments)
        │
        ├── Dev adapters (default): verification codes logged locally, clearly labelled TEST MODE
        └── Production adapters (optional, when configured): SMTP email, Twilio SMS
```

The Android app **never** talks to MongoDB directly and **never** stores database credentials.
The database credentials live only on the backend host.

## 2. Requirements

| Tool | Version |
|------|---------|
| JDK | 21 (Temurin recommended) |
| Maven | 3.9+ (wrapper included: use `./mvnw` / `mvnw.cmd`) |
| MongoDB | 7.x or 8.x local, or MongoDB Atlas |

## 3. Run locally

```bash
# 1. Start MongoDB (any one)
mongod --dbpath <data-dir> --port 27017
# or: docker run -d -p 27017:27017 --name resqai-mongo mongo:7

# 2. Copy the example env file and edit if needed
cp .env.example .env        # loaded automatically at startup

# 3. Run
mvn spring-boot:run          # or: ./mvnw spring-boot:run

# Health check
curl http://localhost:8080/actuator/health   # {"status":"UP"}
```

Default port is **8080** (override with the `PORT` environment variable).
The API base path is **/api/v1**.

## 4. Configure MongoDB Atlas safely

1. Create a free M0 cluster at [mongodb.com/atlas](https://www.mongodb.com/atlas).
2. **Database Access**: create a database user with a strong password.
3. **Network Access**: add the IP of the machine running the backend
   (your server's IP for production; for local development add your current IP).
   Avoid `0.0.0.0/0` for any database holding real user data.
4. Copy the connection string and set it as an environment variable:

```bash
export SPRING_DATA_MONGODB_URI="mongodb+srv://USER:PASSWORD@cluster0.xxxxx.mongodb.net/?retryWrites=true&w=majority"
export SPRING_DATA_MONGODB_DATABASE="resqai"
```

The database and all indexes are created automatically at startup.

## 5. Environment variables

| Variable | Meaning | Default |
|----------|---------|---------|
| `SPRING_DATA_MONGODB_URI` | MongoDB connection string (without database name) | `mongodb://localhost:27017` |
| `SPRING_DATA_MONGODB_DATABASE` | Database name | `resqai` |
| `PORT` | HTTP port (Render injects this) | `8080` |
| `JWT_SECRET` | JWT signing key, min 48 bytes Base64 | dev-only placeholder (**set in production**) |
| `JWT_ACCESS_TTL_SECONDS` | Access token lifetime | `900` |
| `JWT_REFRESH_TTL_SECONDS` | Refresh token lifetime | `2592000` |
| `SMTP_HOST` / `SMTP_USER` / `SMTP_PASSWORD` | Production email provider (enables real verification email) | unset = TEST MODE |
| `RESQAI_SMS_PROVIDER=twilio` + `TWILIO_*` | Production SMS OTP provider | unset = TEST MODE |
| `CORS_ALLOWED_ORIGINS` | Allowed browser origins (mobile app needs none) | empty |

A `.env` file in the project root is loaded automatically at startup
(gitignored; OS environment variables take precedence). See `.env.example`.

## 6. API endpoints

Base path: `/api/v1`. All request/response bodies are JSON.

### Public (no authentication, rate-limited)

| Method | Path | Body | Response |
|--------|------|------|----------|
| POST | `/auth/register` | `{"fullName","email","phone","password","bloodGroup","acceptedTermsAt", ...}` | `201` + `{userId, emailVerificationRequired, phoneVerificationRequired, message}` |
| POST | `/auth/verify-email` | `{"email","token"}` | `200` |
| POST | `/auth/send-phone-otp` | `{"phone"}` | `200` |
| POST | `/auth/verify-phone-otp` | `{"phone","otp"}` | `200` |
| POST | `/auth/login` | `{"identifier","password"}` | `200` + `{accessToken, refreshToken, expiresInSeconds, user}` |
| POST | `/auth/refresh` | `{"refreshToken"}` | `200` + new tokens (rotates) |
| POST | `/auth/logout` | `{"refreshToken"}` | `200` |
| POST | `/auth/forgot-password` | `{"email"}` | `200` (generic response, no enumeration) |
| POST | `/auth/reset-password` | `{"email","resetToken","newPassword"}` | `200` |
| GET | `/emergency-ids/{code}/summary` | — | `200` + `{emergencyId, accountVerified, medicalInfoPolicy}` — **identifies only; discloses no medical data** |
| POST | `/bystander/reports` | `{"reportId","emergencyCode","category","description",...}` | `201` / `200` duplicate — no victim sign-in required |

### Authenticated (Bearer access token)

| Method | Path | Response |
|--------|------|----------|
| GET | `/users/me` | `200` + user + emergency profile |
| PATCH | `/users/me/emergency-profile` | `200` + updated profile |
| GET/PUT | `/users/me/emergency-profile` | `200` + profile |
| GET/POST | `/users/me/emergency-contacts` | `200` / `201` + contact |
| PUT/DELETE | `/users/me/emergency-contacts/{contactId}` | `200` / `204` |
| POST | `/incidents` | `201` created / `200` duplicate (idempotent by client `incidentId`) |
| GET | `/incidents/{incidentId}` | `200` / `404` |
| GET | `/incidents/mine?page=0&size=20` | `200` + paged history |
| POST | `/sync/incidents` | batch upsert of queued incidents |
| POST | `/assessments` | `201` / `200` duplicate — rules-engine metadata sync |

Error responses are uniform: `{"error","message","fieldErrors"?,"retryAfterSeconds"?}`.

## 7. Run tests

```bash
mvn test                     # or: ./mvnw test
```

The integration tests run against a real MongoDB on `localhost:27017` (isolated
`resqai_test` database). On GitHub Actions a `mongo:7` service container is started
automatically by the CI workflow. 53 tests total: unit (password hashing, JWT,
auth service) + full-stack integration (registration → verification → login →
profile/contacts/incidents/reports, cross-user access protection, idempotency,
validation).

## 8. Deploy to Render

1. Push this repository to GitHub.
2. On [render.com](https://render.com): **New → Web Service → connect the repo**.
3. Runtime: **Docker** (Render builds the included Dockerfile).
4. Environment variables (Dashboard → Environment):
   - `SPRING_DATA_MONGODB_URI` = your Atlas connection string
   - `SPRING_DATA_MONGODB_DATABASE` = `resqai`
   - `JWT_SECRET` = a long random Base64 string
   - `MAIL_FROM` + SMTP variables (optional, for real verification email)
   - Twilio variables (optional, for real SMS OTP)
5. Render injects `PORT` automatically; the app binds to it.
6. Health check path for Render: `/actuator/health`.

Verify after deploy: `curl https://<your-service>.onrender.com/actuator/health`
then register a test account through the API.

In MongoDB Atlas → Network Access, add the outbound IP addresses Render uses
(listed in Render's docs) so the backend can reach the cluster.

## 9. Known limitations

- **No live ambulance dispatch, hospital capacity or responder tracking.** The backend
  stores incidents and reports; it never claims an ambulance was dispatched.
- **ABHA integration**: status is stored but no ABDM network connection exists.
- **Email/SMS**: production adapters exist but require real provider credentials;
  without them the app runs in a clearly labelled TEST MODE (codes in server logs only).
- **Assessment results** are rule-based warning indicators, not medical diagnoses.

## 10. Security notes

- Passwords are hashed with Argon2id; plaintext is never stored or logged.
- JWT secrets, database URIs and provider credentials come from environment variables
  only — **never put them in the Android app or in Git**. Anything committed to a public
  repository must be treated as compromised and rotated.
- Every profile/contact/incident/assessment operation derives the owner from the
  validated JWT; client-supplied user IDs are never trusted.
- The Emergency QR code and Emergency ID identify a victim for bystander reporting.
  They are **not** authentication credentials and expose no medical data.
- CORS is empty by default (the Android app needs none); do not open it casually.
- No hardcoded or seeded user accounts exist.


---

# Master Spec Additions (2026-11)

## Two-number Twilio architecture (PART 3)
- **Number A** (`TWILIO_SMS_AMBULANCE_NUMBER`): receives the owner's signed emergency SMS and places the ambulance voice call.
- **Number B** (`TWILIO_BYSTANDER_AI_NUMBER`): places the bystander's AI first-aid call.
- Test destinations: `AMBULANCE_TEST_DESTINATION_NUMBER`, `BYSTANDER_TEST_DESTINATION_NUMBER` (env-only, no rebuild).
- `TwilioConfigurationService` validates both numbers against the live account at startup (ownership + distinctness) and logs a configuration report.

## Owner offline SOS via signed SMS (PART 4-5)
The phone needs NO internet - only cellular SMS:
1. SOS press -> app builds the compact payload `RESQ|1|incidentId|patientId|deviceId|timestamp|nonce|category|name|blood|allergies|conditions|lat|lon|accuracy|lang` and signs it with an **EC P-256 key in Android Keystore** (public key registered via `POST /api/v1/devices/register`).
2. SMS sent with the native SmsManager to Number A.
3. Twilio POSTs to `POST /api/emergency/sms/webhook` -> backend validates Twilio signature, sender, ECDSA signature, timestamp window (+/-10 min), nonce (durable replay store), duplicate incidentId.
4. Incident stored in `emergency_sms_incidents`; ambulance call placed from Number A with `<Say>` TwiML (`/api/v1/twiml/sms/{incidentId}`, repeats twice, EN/HI voice).
5. Call status tracked at `POST /api/emergency/sms/status/{incidentId}`.

Honest statuses on the phone: CREATED / SUBMITTED / SUBMIT_FAILED - submission is never claimed as Twilio receipt. The direct native emergency call remains fully available as the fallback (spec PART 11).

## Dual simultaneous calls (PART 6)
`EmergencyDualCallService` dispatches CALL 1 (ambulance) and CALL 2 (bystander AI) on separate threads immediately after incident creation - independent failures, idempotent per report (ambulance: callStatus guard; bystander: bystanderCallSid guard). Statuses reported separately in the API (`callStatus` vs `bystanderCallStatus`).

## Bystander AI first-aid agent (PART 7-9)
Number B calls the bystander (with their explicit in-app consent, confirmed number, and chosen language EN/HI/MR). The conversation is a **multi-turn voice dialog** driven by Twilio `<Gather input="speech">` (natural speech turn-taking with barge-in) connected to the deterministic `FirstAidOrchestrator`: per-category question/instruction chains for bleeding, unconsciousness, breathing difficulty, seizure, burns, fracture, poisoning and cardiac emergencies, with medical-context cautions (blood thinners, seizure history, diabetes, allergies) injected from the victim's authorized record. Every instruction is audited in `first_aid_guidance_logs`, every conversation turn in `voice_conversation_history`, sessions in `bystander_call_sessions`.

> Documented limitation (per spec 7.1): turn-taking uses Twilio's server-side speech recognition rather than a raw bidirectional Media Stream -> Sarvam streaming pipe. This is the lowest-latency architecture supportable today; the Media Stream WebSocket upgrade is the future latency optimization. It is NOT an IVR menu - the dialog is multi-turn, context-aware and adaptive.

## New environment variables
```
TWILIO_SMS_AMBULANCE_NUMBER=      # Number A
TWILIO_BYSTANDER_AI_NUMBER=       # Number B
AMBULANCE_TEST_DESTINATION_NUMBER=
BYSTANDER_TEST_DESTINATION_NUMBER=
```

## Windows run
`run.bat` in this folder: checks Java, loads `.env`, validates required configuration, builds if needed, and starts the backend with clear failure reporting.

## New collections
`emergency_sms_incidents`, `emergency_sms_nonces`, `bystander_call_sessions`, `voice_conversation_history`, `first_aid_guidance_logs`.
