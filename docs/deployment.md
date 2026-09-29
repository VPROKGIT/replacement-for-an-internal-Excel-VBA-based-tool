# Deployment: the online review environment

The app runs as one Docker image (`Dockerfile`). Everything specific to a host comes from
environment variables, so the same image runs anywhere:

| Variable                     | What                                                            |
|------------------------------|-----------------------------------------------------------------|
| `SPRING_DATASOURCE_URL`      | `jdbc:postgresql://HOST/DATABASE?sslmode=require`               |
| `SPRING_DATASOURCE_USERNAME` | Database user                                                   |
| `SPRING_DATASOURCE_PASSWORD` | Database password                                               |
| `FORMS_SECURITY_USERS`       | Who may sign in: `name:bcrypt-hash,name:bcrypt-hash` (below)    |
| `PORT`                       | Set by the host; the app listens on it (default 8080)           |

The image runs with the `prod` profile (`application-prod.yml`): HTTPS-aware behind the host's
proxy, secure session cookie, small memory and connection footprint, and an example page
("Demo: loan application") created on startup if it is missing.

## Current setup (free)

- **App: [Render](https://render.com) free web service**, Frankfurt, built from this repository's
  `Dockerfile` on every push to `main` (`render.yaml`). 512 MB, 0.1 CPU, no credit card.
- **Database: [Neon](https://neon.com) free PostgreSQL**, Frankfurt. 0.5 GB, 100 compute-hours a
  month, pauses itself after 5 minutes idle. No credit card, doesn't expire. (Render's own free
  PostgreSQL is deleted after 30 days, so it isn't used.)

**What free costs you: a slow first visit.** Render stops the app after 15 minutes without
visitors. The next visit waits for Render to wake it (about a minute) and for the app to start on
0.1 CPU (about 2½ minutes, measured locally with the same limits) — so **about 3 minutes**, once.
After that, pages load normally. Sessions don't survive a stop: reviewers sign in again after a
long break. Limits checked on 2026-09-29; free offers change, so recheck before relying on them.

## First-time setup

You create the accounts; nothing here needs a credit card.

### 1. The database (Neon)

1. Sign up at <https://neon.com> (the Free plan).
2. Create a project: name `forms-review`, **PostgreSQL 16**, region **AWS Europe (Frankfurt)**.
3. On the project dashboard, open **Connect**. Switch **Connection pooling off** (the app runs
   schema migrations, which need a direct connection) and note the **host**, **database**,
   **user** and **password** from the connection string
   `postgresql://USER:PASSWORD@HOST/DATABASE?sslmode=require`.
4. The app needs them in three pieces:
   - `SPRING_DATASOURCE_URL` = `jdbc:postgresql://HOST/DATABASE?sslmode=require`
   - `SPRING_DATASOURCE_USERNAME` = `USER`
   - `SPRING_DATASOURCE_PASSWORD` = `PASSWORD`

### 2. The users

Pick a user name for each reviewer and a strong password (a password manager can generate one).
Hash each password — with Docker Desktop running:

```bash
docker run --rm httpd:2.4-alpine htpasswd -nbBC 10 alice 'her-password'
```

It prints `alice:$2y$10$...`. Join the lines with commas, no spaces:
`alice:$2y$10$...,bob:$2y$10$...` — that is `FORMS_SECURITY_USERS`. Send each person their own
password privately; only the hashes go to the host.

### 3. The app (Render)

1. Sign up at <https://render.com> with your GitHub account (the free Hobby workspace).
2. **New → Blueprint**, connect GitHub and give Render access to this repository only.
3. Render reads `render.yaml` and asks for the four values from steps 1 and 2. Paste them and
   **Apply**.
4. The first deploy builds the image (about 5–10 minutes), then starts the app (about 2½
   minutes). The logs end with `Started FormStructureBuilderApplication` and, the first time,
   `Created the demo page DEMO_LOAN_APPLICATION`.
5. Open the service's URL (`https://form-structure-builder-….onrender.com`), sign in, and check
   the demo page. That URL is the link for reviewers — with `docs/review-guide.md`.

## Day to day

- **Redeploy:** merging to `main` redeploys automatically. Or **Manual Deploy** in Render.
- **Add or remove a reviewer:** edit `FORMS_SECURITY_USERS` in the service's **Environment** tab
  and save — Render restarts the app with the new list.
- **Logs:** the service's **Logs** tab in Render.
- **Demo page:** created on start whenever no page with code `DEMO_LOAN_APPLICATION` exists. A
  reviewer's edits to it are kept; if it is deleted, it comes back on the next start.

### Optional: keep the app awake

To avoid the 3-minute first visit, have a free scheduler such as <https://cron-job.org> open
`https://YOUR-APP.onrender.com/login` every 10 minutes. One always-on service uses about 744 of
Render's 750 free instance hours a month, so it fits — but only for this one service. `/login`
never touches the database, so the database still pauses when nobody works.

## Backup and restore

With Docker Desktop running, using the same PostgreSQL major version as Neon (16):

```bash
docker run --rm -v "$PWD:/backup" postgres:16 pg_dump --no-owner --no-privileges -Fc \
  -d "postgresql://USER:PASSWORD@HOST/DATABASE?sslmode=require" -f /backup/forms-review.dump
```

Restore into an **empty** database (a new Neon project, or another host):

```bash
docker run --rm -v "$PWD:/backup" postgres:16 pg_restore --no-owner --no-privileges \
  -d "postgresql://USER:PASSWORD@NEW_HOST/DATABASE?sslmode=require" /backup/forms-review.dump
```

Neon's free plan keeps no backups of its own worth relying on — take a dump before anything risky.

## Moving to a paid host

If the free tier gets in the way, the same image runs on any Docker host. Within a few euros a
month, the realistic choice is a small virtual server (e.g. Hetzner Cloud, about €4–5 a month —
check current prices): Docker Compose with this image, PostgreSQL and a reverse proxy such as
Caddy for HTTPS. Move the data with the backup/restore above, set the same environment
variables, and point reviewers to the new address.
