# Deploying the sync server to Oracle Cloud

Target: an Always Free instance running Docker, with Caddy terminating TLS for `recuer.de`.

The server is built **on the VM** rather than pushed from a laptop. The free Ampere shape is ARM, so
building there produces a native `arm64` image and needs no registry and no cross-compilation.

---

## 1. The instance

Use **VM.Standard.A1.Flex** (Ampere, ARM) with at least 1 OCPU and 6 GB of RAM. The Always Free
allowance is 4 OCPU and 24 GB, so take more if you like; this workload will not notice.

Avoid `VM.Standard.E2.1.Micro` if you can. It has 1 GB of RAM, and the Gradle build inside the
Docker image will run out of memory. If the Ampere shape is unavailable in your region (Oracle
frequently answers "Out of host capacity"), either retry over a few days, or use the micro shape and
add swap before building:

```sh
sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

Either Ubuntu 24.04 or Oracle Linux 9 works. Commands below are given for both.

Note the instance's **public IP** when it finishes provisioning.

## 2. DNS

At Porkbun, on `recuer.de`, set an A record:

| Type | Host | Answer |
| --- | --- | --- |
| A | (blank, meaning the root) | your instance's public IP |

Leave out `www`; nothing serves it. Then wait for it to resolve before going further, because Caddy
will ask Let's Encrypt for a certificate on first start and a failed attempt counts against your
rate limit:

```sh
dig +short recuer.de          # must print your instance IP
```

## 3. Open the firewall, in both places

**This is the step that catches everyone.** Oracle blocks inbound traffic in two independent layers
and opening one does nothing on its own.

### Layer 1: the VCN security list

Console → Networking → Virtual Cloud Networks → your VCN → Subnets → your subnet → Security Lists →
Default Security List → **Add Ingress Rules**. Add two, both stateful:

| Source CIDR | Protocol | Destination port |
| --- | --- | --- |
| `0.0.0.0/0` | TCP | 80 |
| `0.0.0.0/0` | TCP | 443 |

Port 80 is not optional. Let's Encrypt's HTTP-01 challenge uses it, and Caddy redirects to HTTPS
afterwards.

### Layer 2: the firewall inside the instance

Oracle's images ship with rules that drop everything except SSH. SSH in and run:

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

## 4. Install Docker

**Ubuntu:**
```sh
sudo apt-get update
sudo apt-get install -y docker.io docker-compose-v2 git
sudo systemctl enable --now docker
sudo usermod -aG docker $USER
```

**Oracle Linux:**
```sh
sudo dnf install -y dnf-utils git
sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker $USER
```

Log out and back in so the group change takes effect.

## 5. Deploy

```sh
git clone https://github.com/sergionsz/life-insights.git
cd life-insights

cp deploy/.env.example deploy/.env
chmod 600 deploy/.env

# Generate the two secrets. Do not invent them by hand.
echo "SYNC_TOKEN=$(openssl rand -base64 32)"
echo "POSTGRES_PASSWORD=$(openssl rand -base64 32)"
```

Put those values into `deploy/.env`, along with `SYNC_DOMAIN=recuer.de` and your email for
`ACME_EMAIL`. **Keep a copy of `SYNC_TOKEN`**; you need to type it into the phone.

```sh
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
```

The first build takes several minutes: it downloads Gradle and the dependencies, then compiles.

## 6. Check it

On the box:
```sh
curl -s localhost:8080/health                    # -> ok
docker compose -f deploy/docker-compose.yml --env-file deploy/.env ps
```

From your laptop:
```sh
curl -s https://recuer.de/health                 # -> ok, with a valid certificate
curl -s -o /dev/null -w '%{http_code}\n' https://recuer.de/v1/sync/status   # -> 401
```

A `401` there is the correct answer and a good sign: the server is up, TLS works, and it is refusing
an unauthenticated request. With the token:

```sh
curl -s -H "Authorization: Bearer YOUR_TOKEN" https://recuer.de/v1/sync/status
# {"serverSeq":0,"checkIns":0,"dailyMetrics":0,"tags":0}
```

## 7. Point the phone at it

Build and install a release APK with the sync feature (`./gradlew :app:assembleRelease`), then in
the app: **Settings → Sync**.

- Server address: `https://recuer.de`
- Sync token: the `SYNC_TOKEN` value
- **Save**, then turn on **Sync automatically**, then **Sync now**

The status line underneath is the thing to read. "Last synced ..., nothing waiting to upload" means
it worked. A number that stays above zero means the server is not accepting the data, and the error
line below says why.

Check the server saw it:
```sh
curl -s -H "Authorization: Bearer YOUR_TOKEN" https://recuer.de/v1/sync/status
```

## Backups

A sidecar dumps the database on start and daily after that, into `deploy/backups/`, keeping 14 days.

```sh
ls -lh deploy/backups/
```

**These sit on the same disk as the database.** They protect against the realistic failures, which
are a bad migration, a mistaken `docker compose down -v`, and deleting something in the app and
regretting it. They do not protect against losing the instance. Copy them off periodically:

```sh
# from your laptop
scp -r ubuntu@recuer.de:life-insights/deploy/backups ~/life-insights-backups/
```

Restoring:

```sh
gunzip -c deploy/backups/insights-YYYYMMDDTHHMMSSZ.sql.gz | \
  docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T db \
  psql -U insights -d insights
```

Worth knowing: the phone holds a full copy too, and Settings can export JSON or CSV. Between the
phone, the server and the dumps there are three copies, which is roughly the right number for data
that cannot be reconstructed.

## Updating

```sh
git pull
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
```

Schema migrations run automatically at startup and are idempotent, so a redeploy that changes
nothing is safe.

## When it does not work

**`curl https://recuer.de/health` hangs, but `curl localhost:8080/health` works on the box.**
A firewall. You have almost certainly done one of the two layers in step 3 and not the other. Check
the VCN ingress rules in the console, then `sudo iptables -L INPUT -n --line-numbers` on the box.

**Caddy logs "could not get certificate".** Either DNS has not propagated (`dig +short recuer.de`
from somewhere other than the VM), or port 80 is not reachable. Let's Encrypt validates over port 80
even though the result is used for 443.

```sh
docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs caddy
```

**The phone says "The server rejected the sync token".** The token in Settings does not match
`SYNC_TOKEN` in `deploy/.env`. Watch for a trailing space or newline from copy-paste; the base64
output ends with `=` and it is easy to clip a character.

**The phone says "No sync server at that address".** The address is wrong or reached something else.
It should be `https://recuer.de` with no trailing path.

**The build is killed partway through.** Out of memory, on the micro shape. Add swap (step 1).

**The server refuses to start** and logs `Cannot start: SYNC_TOKEN ...`. Deliberate: it will not run
without a token rather than serve your history to anyone who finds the host. Check `deploy/.env` is
present and that you passed `--env-file`.
