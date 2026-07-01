# Keys

Private SSH keys in this directory are local deployment material and must not be
committed.

Release signing material is also kept here locally:

```text
keys/xray-ssh-tunnel-release.p12
keys/xray-ssh-tunnel-release.pass
```

These files are ignored by git. The release certificate must match the GitHub
release APKs from v1.5 onward.

For a fresh deployment, generate a key and copy it into the Android assets:

```bash
ssh-keygen -t rsa -b 4096 -m PEM -N '' -C 'xray-ssh-tunnel-151' -f keys/phone_tunnel_151_key
cp keys/phone_tunnel_151_key app/src/main/assets-bundled/phone_tunnel_151_key
```

Install only the public key on the server, restricted to the required port
forward target:

```text
restrict,port-forwarding,permitopen="127.0.0.1:443",permitopen="localhost:443" ssh-rsa ... xray-ssh-tunnel-151
```
