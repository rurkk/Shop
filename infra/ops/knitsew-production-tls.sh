#!/bin/sh
set -eu

[ "${RENEWED_LINEAGE:?Certbot did not specify the renewed certificate}" = /etc/letsencrypt/live/knitsew.ru ] || exit 0
cd /opt/knitsew-production/tls
umask 077

staging=$(mktemp -d .renewal.XXXXXXXX)
trap 'rm -rf -- "$staging"' EXIT

install -m 0644 "$RENEWED_LINEAGE/fullchain.pem" "$staging/fullchain.pem"
install -m 0600 "$RENEWED_LINEAGE/privkey.pem" "$staging/privkey.pem"
openssl x509 -in "$staging/fullchain.pem" -noout -pubkey > "$staging/cert.pub"
openssl pkey -in "$staging/privkey.pem" -pubout > "$staging/key.pub"
cmp "$staging/cert.pub" "$staging/key.pub"

mv -f -- "$staging/fullchain.pem" fullchain.pem
mv -f -- "$staging/privkey.pem" privkey.pem

if [ "${KNITSEW_INSTALL_INITIAL_TLS:-0}" = 1 ]; then
    exit 0
fi

/usr/local/sbin/knitsew-production-compose exec -T nginx nginx -t
/usr/local/sbin/knitsew-production-compose exec -T nginx nginx -s reload
