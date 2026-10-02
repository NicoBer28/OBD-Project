# Deploying the API to Oracle Cloud

Do these steps once, in order. Each step says **where** you run it and how to
**check** it worked before moving on. After step 12, every push to
`development` that changes `api/` redeploys on its own.

The server runs two containers: the Spring Boot API and Caddy (HTTPS). The
database stays on Supabase.

Replace `<server-ip>` with your VM's public IP everywhere below.

---

### Step 1 — Open ports 80 and 443 in Oracle

**Where:** Oracle Cloud console (browser)

1. Go to **Networking → Virtual Cloud Networks** and open your VCN.
2. Open the **Subnet** your VM uses, then its **Security List**.
3. Click **Add Ingress Rules** and add this rule:
   - Stateless: unchecked
   - Source CIDR: `0.0.0.0/0`
   - IP Protocol: **TCP**
   - Source Port Range: leave empty
   - Destination Port Range: `80`
4. Add a second rule, identical but with Destination Port Range `443`.

**Check:** the Security List shows both rules.

---

### Step 2 — Connect to the server

**Where:** your Mac

```bash
ssh ubuntu@<server-ip>
```

Steps 3–8 run inside this SSH session.

---

### Step 3 — Open the ports on the server's firewall

**Where:** server

Oracle's Ubuntu images have their own firewall that blocks everything but SSH.

```bash
sudo iptables -I INPUT 6 -p tcp -m state --state NEW -m multiport --dports 80,443 -j ACCEPT
sudo netfilter-persistent save
```

**Check:** `sudo iptables -L INPUT -n --line-numbers` shows an ACCEPT line
with `multiport dports 80,443`.

---

### Step 4 — Install Docker

**Where:** server

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
exit
```

Then reconnect (`ssh ubuntu@<server-ip>`) so the group change applies.

**Check:** `docker run --rm hello-world` prints "Hello from Docker!" without
needing `sudo`.

---

### Step 5 — Add swap (only on a 1 GB server)

**Where:** server

Skip this step if `free -h` shows 4 GB or more of memory. On the 1 GB free
AMD server, the build runs out of memory without it.

```bash
sudo fallocate -l 4G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

**Check:** `free -h` shows a 4.0Gi Swap line.

---

### Step 6 — Stop any old copy of the API

**Where:** server

If the API already runs on this server from before, it will clash with the new
container on port 8080.

```bash
sudo ss -tlnp | grep -E ':(80|443|8080) '
```

If this prints nothing, go to step 7. Otherwise, stop whatever it shows (for
example `sudo systemctl disable --now <service-name>`, or
`docker stop <container>`).

**Check:** the command above prints nothing.

---

### Step 7 — Clone the repo and add the production secrets

The deploy workflow does not copy files: it runs `git fetch` and
`reset --hard` inside a clone at `~/OBD-Project`, so that clone has to exist
first.

**Where:** server

```bash
cd ~
git clone https://github.com/NicoBer28/OBD-Project.git OBD-Project
cd OBD-Project && git checkout development
```

If the repository is private, the server needs read access — the simplest
route is a **read-only deploy key**: `ssh-keygen -t ed25519 -f ~/.ssh/id_repo`
on the server, then add the `.pub` under the repo's **Settings → Deploy keys**,
and clone with the SSH URL.

**Check:** `ls ~/OBD-Project/api/deploy/docker-compose.yml` exists.

Now the configuration. Everything the stack needs lives in one file,
`~/OBD-Project/api/deploy/.env`: the API reads it through `env_file`, and
Caddy reads the two domain names from it by compose substitution. It is
git-ignored, so the workflow's `git reset --hard` never touches it.

```bash
cd ~/OBD-Project/api/deploy
nano .env
```

Paste the production values — the same keys as `api/.env.example` — and make
sure:

- no value is wrapped in quotes (`DB_PASSWORD=abc`, not `DB_PASSWORD="abc"`)
- there is **no** `SPRING_PROFILES_ACTIVE=dev` line
- the mail variables from [Turning email on](#turning-email-on) are **all
  present**. The API **will not start** otherwise: without a `dev` profile and
  with `MAIL_PROVIDER` unset or `log`, it refuses to boot rather than write
  live tokens to the log and leave every new account unable to confirm its
  address. Do that section before the first deploy — it is no longer optional

Then lock it down, since it holds the database password and the JWT secret:

```bash
chmod 600 .env
```

**Check:** `ls -l ~/OBD-Project/api/deploy/.env` shows `-rw-------`.

---

### Step 8 — Choose the two addresses

**Where:** server

Two names are needed, and **both are required** — compose refuses to start
without them, API container included:

- `API_DOMAIN` — what the Flutter app talks to, e.g. `api.obidi.com.ar`
- `WEB_DOMAIN` — serves the pages the verification and password-reset emails
  link to, e.g. `www.obidi.com.ar`

Append them to the same `.env` from step 7:

```bash
cd ~/OBD-Project/api/deploy
cat >> .env <<'EOF'
API_DOMAIN=api.obidi.com.ar
WEB_DOMAIN=www.obidi.com.ar
EOF
```

Both DNS records must be **DNS only** (grey cloud in Cloudflare): Caddy needs
to reach Let's Encrypt directly, and a proxied record breaks the certificate
challenge. Without a domain, use the free `sslip.io` form — your IP with
dashes, e.g. `129-151-10-20.sslip.io` — for both.

**Check:** `grep DOMAIN ~/OBD-Project/api/deploy/.env` shows both lines.

You're done on the server. You can `exit`.

---

### Step 9 — Create an SSH key for GitHub Actions

**Where:** your Mac

```bash
ssh-keygen -t ed25519 -f ~/.ssh/obd_deploy -N "" -C "github-actions-deploy"
ssh-copy-id -i ~/.ssh/obd_deploy.pub ubuntu@<server-ip>
```

**Check:** `ssh -i ~/.ssh/obd_deploy ubuntu@<server-ip> echo ok` prints `ok`
without asking for a password.

---

### Step 10 — Add the secrets to GitHub

**Where:** GitHub → the repo → **Settings → Secrets and variables → Actions →
New repository secret** (needs admin on the repo).

Create these four:

| Name | Value | How to get it |
|---|---|---|
| `ORACLE_HOST` | `<server-ip>` | |
| `ORACLE_USER` | `ubuntu` | |
| `ORACLE_SSH_KEY` | the private key | `cat ~/.ssh/obd_deploy` (copy everything, including the BEGIN/END lines) |
| `ORACLE_KNOWN_HOSTS` | the server's fingerprint | `ssh-keyscan -H <server-ip>` (copy all lines) |

**Check:** the Secrets page lists all four names.

---

### Step 11 — Push the deploy files

**Where:** your Mac

Commit `api/deploy/` (including `api/deploy/web/`, the pages the emailed links
open) and `.github/workflows/deploy-api.yml` on `development`, then push. The
push itself starts the first deploy.

---

### Step 12 — Watch the first deploy

**Where:** GitHub → **Actions** → **Deploy API**

The first run takes several minutes (it downloads Maven dependencies). All
steps should turn green. If *Health check* fails, its output shows the API's
last log lines.

**Check:** open `https://<your-domain>/actuator/health` in a browser. It
should show `{"status":"UP"}` with a valid padlock.

Finally, point the Flutter app's API base URL at `https://<your-domain>`.

---

## Turning email on

**Do this before the first deploy, not after.** Email is not a finishing
touch any more: an account can do nothing until it confirms its address, so an
API with no working provider is an API nobody can use. It knows that about
itself and refuses to start — `LoggingMailer` throws unless the `dev` or
`test` profile is active, so a container whose `.env` is missing these
variables crashloops and the deploy's health check fails with the reason.

Without these steps the links in those emails also point at `localhost`, which
is the other half of the same problem.

### Step A — Verify a sending domain in Resend

In Resend: **Domains → Add Domain** → `mail.obidi.com.ar`. A **subdomain**,
not the apex: sending reputation is tracked per domain, so this keeps the
app's mail separate from anything else the domain ever sends.

Resend shows the DNS records to publish — copy them, don't invent them. In
Cloudflare, two traps account for nearly every failed verification:

- **Never proxy them.** DKIM records are often `CNAME`s, and Cloudflare
  defaults new `CNAME`s to proxied (orange cloud). Click it to **DNS only**
  (grey). A proxied DKIM record can never verify.
- **Don't repeat the zone.** Cloudflare appends `obidi.com.ar` to what you
  type. If Resend says `resend._domainkey.mail.obidi.com.ar`, enter
  `resend._domainkey.mail`.

Hit **Verify**. Usually green within 15 minutes.

**Check:** the domain shows *Verified* in Resend.

### Step B — Add DMARC

Not required for Resend to verify, but it is the record that actually stops
someone impersonating the domain. In Cloudflare:

```
Type: TXT    Name: _dmarc.mail
Value: v=DMARC1; p=none; rua=mailto:dmarc@obidi.com.ar
```

`p=none` means "report, change nothing". Read the reports for a week, then
tighten to `p=quarantine` and later `p=reject`.

### Step C — Point both hostnames at the server

Two `A` records in Cloudflare, both **DNS only** (grey cloud — Caddy needs to
reach Let's Encrypt directly to issue certificates, and a proxied record
breaks the challenge):

```
api      A   <server-ip>     DNS only
www      A   <server-ip>     DNS only
```

`api.obidi.com.ar` serves the API; `www.obidi.com.ar` serves the two pages the
emailed links open.

Nothing is served on the bare apex (`obidi.com.ar`). Leaving it unconfigured
is fine — just know that someone typing it by hand gets nothing. If you want
it to land somewhere, the usual move is an apex record plus a Caddy block that
redirects to `www`, which can be added later without touching anything else.

**Check:** `dig +short api.obidi.com.ar` and `dig +short www.obidi.com.ar`
both return the server's IP.

### Step D — Set the mail variables on the server

All of it goes in the same `~/OBD-Project/api/deploy/.env` from steps 7 and 8:

```bash
MAIL_PROVIDER=resend
RESEND_API_KEY=re_...                                  # Sending access only
MAIL_FROM=no-reply@mail.obidi.com.ar
MAIL_FROM_NAME=OBIDI
MAIL_VERIFY_URL_BASE=https://www.obidi.com.ar/verify-email
MAIL_RESET_URL_BASE=https://www.obidi.com.ar/reset-password
```

Three things to get right:

- **`MAIL_PROVIDER=log` in production is refused, not merely discouraged.**
  It writes the live token to the application log, throwing away the point of
  storing only its hash, and with the email gate in place it would lock out
  every account that registers. The API fails to start instead; the message in
  `docker compose logs api` names the variables to set.
- **The two `MAIL_*_URL_BASE` values are the step people forget.** Without
  them the emails arrive with `localhost` links.
- **`CORS_ORIGINS` needs nothing added for these pages.** Caddy proxies
  `/api` on `WEB_DOMAIN` to the same API container, so the pages call
  relative URLs and the requests are same-origin. That is deliberate — it is
  one less thing to keep in sync.

### Step E — Deploy and check

```bash
cd ~/OBD-Project/api/deploy
docker compose up -d --build
```

**Check,** in order:

```bash
# 1. The pages are served, and a token in the path resolves to the page
curl -o /dev/null -w '%{http_code}\n' https://www.obidi.com.ar/verify-email/anything

# 2. /api on the web host reaches the API
curl -o /dev/null -w '%{http_code}\n' https://www.obidi.com.ar/api/v1/models  # 401

# 3. A real send. Until Step A is green, Resend only accepts
#    onboarding@resend.dev as the sender and only your own signup address as
#    the recipient.
curl -X POST https://api.obidi.com.ar/api/v1/auth/forgot-password \
  -H 'Content-Type: application/json' -d '{"userEmail":"you@example.com"}'
```

Then open the link in the real email. It should land on the page, say
*«Confirmando tu correo…»* and then *«¡Listo!»*.

### Step F — Later: let the app take the links over

The same URLs become universal links once the Flutter app claims them. Drop
`apple-app-site-association` and `assetlinks.json` into
`deploy/web/.well-known/` — Caddy serves them from there with no further
configuration — and the pages stay as the fallback for anyone who opens the
mail on a laptop.

## After setup

| I want to… | Do this |
|---|---|
| Deploy a change | Push to `development` with changes under `api/`. Nothing else. |
| Redeploy without a change | Actions → Deploy API → Run workflow |
| See the logs | On the server: `cd ~/OBD-Project/api/deploy && docker compose logs -f api` |
| Change a secret or a domain | Edit `~/OBD-Project/api/deploy/.env`, then `cd ~/OBD-Project/api/deploy && docker compose up -d --force-recreate` |
| Roll back | Revert the commit on `development` and push |
