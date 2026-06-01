# Keys

Private SSH keys in this directory are local deployment material and must not be
committed.

For a fresh deployment, generate a key and copy it into the Android assets:

```bash
ssh-keygen -t rsa -b 4096 -m PEM -N '' -C 'xray-phone-tunnel-107-rsa' -f keys/phone_tunnel_rsa
cp keys/phone_tunnel_rsa app/src/main/assets/phone_tunnel_key
```

Install only the public key on the server, restricted to the required port
forward target:

```text
restrict,port-forwarding,permitopen="127.0.0.1:443",permitopen="localhost:443" ssh-rsa ... xray-phone-tunnel-107-rsa
```
