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

### Step 7 — Put the production secrets on the server

**Where:** your Mac (open a second terminal, from the project folder)

```bash
scp api/.env.prod ubuntu@<server-ip>:/tmp/api.env
```

**Where:** server

```bash
sudo mkdir -p /etc/obd
sudo mv /tmp/api.env /etc/obd/api.env
sudo chmod 600 /etc/obd/api.env
```

Open it with `sudo nano /etc/obd/api.env` and make sure:

- no value is wrapped in quotes (`DB_PASSWORD=abc`, not `DB_PASSWORD="abc"`)
- there is **no** `SPRING_PROFILES_ACTIVE=dev` line

**Check:** `sudo ls -l /etc/obd/api.env` shows `-rw-------`.

---

### Step 8 — Choose the API's address

**Where:** server

If you have a domain, first create a DNS A record pointing it at
`<server-ip>`. If not, use the free `sslip.io` name: your IP with dashes,
e.g. `129-151-10-20.sslip.io`.

```bash
mkdir -p ~/obd-api/deploy
echo "API_DOMAIN=129-151-10-20.sslip.io" > ~/obd-api/deploy/.env
```

**Check:** `cat ~/obd-api/deploy/.env` shows your domain.

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

Commit `api/deploy/` and `.github/workflows/deploy-api.yml` on `development`
and push. The push itself starts the first deploy.

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

## After setup

| I want to… | Do this |
|---|---|
| Deploy a change | Push to `development` with changes under `api/`. Nothing else. |
| Redeploy without a change | Actions → Deploy API → Run workflow |
| See the logs | On the server: `cd ~/obd-api/deploy && docker compose logs -f api` |
| Change a secret | Edit `/etc/obd/api.env`, then `cd ~/obd-api/deploy && docker compose up -d --force-recreate api` |
| Roll back | Revert the commit on `development` and push |
