# The auth tests with what they need (bash, curl, jq), so they run the same from any shell. See ../compose.yaml.
FROM alpine:3.20

RUN apk add --no-cache bash curl jq

COPY . /auth-test

# Through bash, so the scripts needn't be executable in a Windows checkout
ENTRYPOINT ["bash", "/auth-test/auth-test.sh"]
