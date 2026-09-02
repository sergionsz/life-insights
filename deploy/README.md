# Deploying the sync server to Oracle Cloud

A native install: Postgres and Caddy from the distribution's packages, the server as a systemd
service, backups on a systemd timer. No containers.

Nothing is compiled on the VM. The server is built here as a plain JVM distribution and copied
across, which works regardless of whether the instance is ARM or x86, and means a small instance
never has to run Gradle.

---

## 1. The instance

Any Always Free shape works, including `VM.Standard.E2.1.Micro` with its 1 GB of RAM, because
nothing is built on the box. `VM.Standard.A1.Flex` (Ampere, ARM) is still the better choice if it
is available: the Always Free allowance is 4 OCPU and 24 GB. Oracle frequently answers "Out of host
capacity" for Ampere; retrying over a few days usually works.

Either Ubuntu 24.04 or Oracle Linux 9. Commands are given for both.

Note the **public IP** when it finishes provisioning.

## 2. DNS

At Porkbun, on `recuer.de`, add an A record with an empty host (the root) pointing at the public IP.

Wait for it before starting Caddy. It asks Let's Encrypt for a certificate the moment it starts, and
failed attempts count against your rate limit:

```sh
dig +short recuer.de          # must print the instance IP
```

## 3. Open the firewall, in both places

**This is the step that catches everyone.** Oracle blocks inbound traffic in two independent layers,
and opening one does nothing on its own. The symptom of doing only one is a connection that hangs
with no error.

### Layer 1: the VCN security list

Console → Networking → Virtual Cloud Networks → your VCN → Subnets → your subnet → Security Lists →
Default Security List → **Add Ingress Rules**. Two rules, both stateful:

| Source CIDR | Protocol | Destination port |
| --- | --- | --- |
| `0.0.0.0/0` | TCP | 80 |
| `0.0.0.0/0` | TCP | 443 |

Port 80 is not optional. Let's Encrypt validates over it even though the certificate is used on 443.

### Layer 2: the firewall inside the instance

**Ubuntu:**
```sh
sudo iptables -I INPUT -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

**Oracle Linux:**
```sh
sudo firewall-cmd --permanent --add-port=80/tcp
sudo firewall-cmd --permanent --add-port=443/tcp
sudo firewall-cmd --reload
```

## 4. Install the packages

**Ubuntu:**
```sh
sudo apt-get update
sudo apt-get install -y postgresql openjdk-21-jre-headless debian-keyring debian-archive-keyring apt-transport-https curl

curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
  | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
  | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt-get update && sudo apt-get install -y caddy
```

**Oracle Linux:**
```sh
sudo dnf install -y postgresql-server java-21-openjdk-headless curl
sudo postgresql-setup --initdb
sudo systemctl enable --now postgresql

sudo dnf install -y 'dnf-command(copr)'
sudo dnf copr enable -y @caddy/caddy
sudo dnf install -y caddy
```

The server targets Java 17; a 21 runtime runs it fine and is what both distributions ship.

## 5. Create the database

```sh
sudo -u postgres psql <<'SQL'
CREATE USER insights WITH PASSWORD 'CHANGE_ME';
CREATE DATABASE insights OWNER insights;
SQL
```

Generate the password rather than inventing one, and keep it for the next step:

```sh
openssl rand -base64 32
```

Postgres listens on localhost only by default in both distributions. Worth confirming, because it
holds everything:

```sh
sudo -u postgres psql -c "SHOW listen_addresses;"   # localhost
ss -lntp | grep 5432                                # 127.0.0.1:5432 only
```

## 6. Configure and install the service

```sh
sudo useradd --system --no-create-home --shell /usr/sbin/nologin lifeinsights
sudo mkdir -p /etc/life-insights
```

Write `/etc/life-insights/env`. The token is what you paste into the phone, so keep a copy:

```sh
sudo tee /etc/life-insights/env >/dev/null <<EOF
DATABASE_URL=jdbc:postgresql://localhost:5432/insights
DATABASE_USER=insights
DATABASE_PASSWORD=the-password-from-step-5
SYNC_TOKEN=$(openssl rand -base64 32)
PORT=8080
EOF

sudo chown root:root /etc/life-insights/env
sudo chmod 600 /etc/life-insights/env
sudo grep SYNC_TOKEN /etc/life-insights/env      # copy this for the phone
```

Mode 600 and root-owned is deliberate: systemd reads the file before dropping privileges, so the
service account never needs to be able to read the secrets it runs with.

Install the unit files, the backup script and the Caddy config from this directory:

```sh
# from your laptop, in the repository
scp deploy/life-insights.service deploy/life-insights-backup.service \
    deploy/life-insights-backup.timer ubuntu@recuer.de:/tmp/
scp deploy/backup.sh deploy/Caddyfile ubuntu@recuer.de:/tmp/

# on the VM
sudo mv /tmp/life-insights*.service /tmp/life-insights-backup.timer /etc/systemd/system/
sudo mv /tmp/backup.sh /usr/local/bin/life-insights-backup
sudo chmod +x /usr/local/bin/life-insights-backup
sudo mv /tmp/Caddyfile /etc/caddy/Caddyfile
sudo systemctl daemon-reload
```

Edit the email address at the top of `/etc/caddy/Caddyfile` before starting Caddy.

## 7. Deploy the server

From your laptop, in the repository:

```sh
deploy/deploy.sh ubuntu@recuer.de
```

That builds the distribution, copies it to `/opt/life-insights`, restarts the service and waits for
it to answer. It is also the command for every future update.

Then enable everything at boot:

```sh
sudo systemctl enable life-insights
sudo systemctl enable --now life-insights-backup.timer
sudo systemctl reload caddy
```

## 8. Check it

On the box:
```sh
systemctl status life-insights
curl -s localhost:8080/health                    # ok
sudo journalctl -u life-insights -n 20
```

From your laptop:
```sh
curl -s https://recuer.de/health                 # ok, valid certificate
curl -s -o /dev/null -w '%{http_code}\n' https://recuer.de/v1/sync/status   # 401
curl -s -H "Authorization: Bearer YOUR_TOKEN" https://recuer.de/v1/sync/status
# {"serverSeq":0,"checkIns":0,"dailyMetrics":0,"tags":0}
```

A `401` without the token is the right answer: the server is up, TLS works, and it is refusing an
unauthenticated request.

## 9. Point the phone at it

Build and install the app (`./gradlew :app:assembleRelease`), then **Settings → Sync**:

- Server address: `https://recuer.de`
- Sync token: the `SYNC_TOKEN` value
- **Save**, turn on **Sync automatically**, then **Sync now**

Read the status line underneath. "Last synced ..., nothing waiting to upload" means it worked. A
number that stays above zero means the server is not accepting the data, and the line below says
why.

## Backups

The timer dumps the database daily into `/var/backups/life-insights`, keeping 14 days. Run one by
hand to check it works rather than finding out when you need it:

```sh
sudo systemctl start life-insights-backup
sudo systemctl list-timers life-insights-backup.timer
ls -lh /var/backups/life-insights/
```

**These sit on the same disk as the database.** They cover the realistic failures: a bad migration,
a dropped table, deleting something in the app and regretting it. They do not cover losing the
instance. Copy them off now and then:

```sh
rsync -av ubuntu@recuer.de:/var/backups/life-insights/ ~/life-insights-backups/
```

Restoring:

```sh
sudo systemctl stop life-insights
gunzip -c /var/backups/life-insights/insights-YYYYMMDDTHHMMSSZ.sql.gz \
  | sudo -u postgres psql -d insights
sudo systemctl start life-insights
```

The phone holds a full copy too, and Settings exports JSON or CSV. Between the phone, the server and
the dumps that is three copies, which is about right for data that cannot be reconstructed.

## Updating

```sh
deploy/deploy.sh ubuntu@recuer.de
```

Schema migrations run at startup and are idempotent, so a redeploy that changes nothing is safe.

Distribution packages update through the usual channel; on Ubuntu, `unattended-upgrades` covers
Postgres and Caddy security fixes without you doing anything.

One thing native packaging does not give you for free: a **Postgres major version upgrade** is
manual. When the distribution moves from 16 to 17, either `pg_upgrade` or dump and restore. The
daily dumps make the second route easy, and it is a once-every-few-years job.

## When it does not work

**`systemctl status` says `inactive (dead)` rather than `failed`.** The configuration is wrong. The
server logs why and exits cleanly, on purpose, because restarting on a bad token would spin forever
without fixing anything:

```sh
sudo journalctl -u life-insights -n 20     # look for "Cannot start:"
```

**`https://recuer.de/health` hangs but `curl localhost:8080/health` works on the box.** A firewall.
You have almost certainly done one of the two layers in step 3 and not the other. Check the VCN
ingress rules in the console, then `sudo iptables -L INPUT -n --line-numbers` on the box.

**Caddy logs "could not get certificate".** Either DNS has not propagated (check `dig +short
recuer.de` from somewhere other than the VM) or port 80 is unreachable.

```sh
sudo journalctl -u caddy -n 50
```

**The service starts, then exits, repeatedly.** Usually the database: wrong password, or Postgres
not running. `sudo journalctl -u life-insights -n 50` shows the connection error. systemd restarts
it every 5 seconds, so the log fills quickly.

**The phone says "The server rejected the sync token".** It does not match `SYNC_TOKEN` in
`/etc/life-insights/env`. Watch for a clipped character: the base64 output ends in `=` and is easy
to truncate when copying.

**The phone says "No sync server at that address".** Should be `https://recuer.de`, with no trailing
path.
