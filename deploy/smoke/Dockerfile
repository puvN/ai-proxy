FROM alpine:3.20

RUN apk add --no-cache bash curl jq

COPY smoke-test.sh /usr/local/bin/smoke-test.sh
RUN chmod +x /usr/local/bin/smoke-test.sh

ENTRYPOINT ["/usr/local/bin/smoke-test.sh"]
