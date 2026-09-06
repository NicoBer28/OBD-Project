#!/usr/bin/env bash
# Smoke-test the auth endpoints against a running instance of the API.
# Usage: ./scripts/test-endpoints.sh
#
# Requires: curl. Uses jq for pretty-printing/field extraction if installed
# (brew install jq / apt install jq), otherwise falls back to grep/sed.
# Assumes the app is already running on $BASE_URL (see README.md "Running").

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
BASE="$BASE_URL/api/v1/auth"
JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT

HAVE_JQ=0
command -v jq >/dev/null 2>&1 && HAVE_JQ=1

# Unique email each run so re-running the script doesn't hit "email already
# in use" (the in-memory UserRepository is wiped on every app restart, but
# persists across script runs while the app keeps running).
EMAIL="ada+$(date +%s)@example.com"
PASSWORD="supersecret123"

line() { printf '\n\033[1;34m== %s ==\033[0m\n' "$1"; }
fail() { printf '\033[1;31mFAILED\033[0m: %s\n' "$1"; exit 1; }

# Prints $1 as pretty JSON if jq is available, else raw.
print_json() {
  if [ "$HAVE_JQ" = "1" ]; then
    echo "$1" | jq . 2>/dev/null || echo "$1"
  else
    echo "$1"
  fi
}

# Extracts a top-level string field ($2) from JSON body ($1).
extract_field() {
  local body="$1" field="$2"
  if [ "$HAVE_JQ" = "1" ]; then
    echo "$body" | jq -r --arg f "$field" '.[$f] // empty'
  else
    echo "$body" | grep -o "\"$field\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" \
      | head -1 | sed -E "s/.*\"$field\"[[:space:]]*:[[:space:]]*\"([^\"]*)\"/\1/"
  fi
}

line "1. Register ($EMAIL)"
REGISTER_BODY=$(curl -sS -c "$JAR" -X POST "$BASE/register" \
  -H "Content-Type: application/json" \
  -d "{
    \"userName\": \"Ada\",
    \"userLastName\": \"Lovelace\",
    \"userEMail\": \"$EMAIL\",
    \"userPassword\": \"$PASSWORD\",
    \"userPhone\": \"+39 320 1234567\"
  }")
print_json "$REGISTER_BODY"
ACCESS_TOKEN=$(extract_field "$REGISTER_BODY" accessToken)
[ -n "$ACCESS_TOKEN" ] || fail "no accessToken in register response"

line "2. Login"
LOGIN_BODY=$(curl -sS -c "$JAR" -X POST "$BASE/login" \
  -H "Content-Type: application/json" \
  -d "{\"userMail\": \"$EMAIL\", \"userPassword\": \"$PASSWORD\"}")
print_json "$LOGIN_BODY"
ACCESS_TOKEN=$(extract_field "$LOGIN_BODY" accessToken)
[ -n "$ACCESS_TOKEN" ] || fail "no accessToken in login response"

line "3. Refresh"
REFRESH_BODY=$(curl -sS -b "$JAR" -c "$JAR" -X POST "$BASE/refresh")
print_json "$REFRESH_BODY"
ACCESS_TOKEN=$(extract_field "$REFRESH_BODY" accessToken)
[ -n "$ACCESS_TOKEN" ] || fail "no accessToken in refresh response"

line "4. Logout"
LOGOUT_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -b "$JAR" -X POST "$BASE/logout" \
  -H "Authorization: Bearer $ACCESS_TOKEN")
echo "status: $LOGOUT_STATUS"
[ "$LOGOUT_STATUS" = "204" ] || fail "expected 204 from logout, got $LOGOUT_STATUS"

line "5. Refresh again after logout (expect 401 - token family revoked)"
POST_LOGOUT_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -b "$JAR" -X POST "$BASE/refresh")
echo "status: $POST_LOGOUT_STATUS"
[ "$POST_LOGOUT_STATUS" = "401" ] || fail "expected 401 after logout, got $POST_LOGOUT_STATUS"

line "6. Duplicate email on register (expect 409)"
DUP_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/register" \
  -H "Content-Type: application/json" \
  -d "{\"userName\":\"Ada\",\"userLastName\":\"L\",\"userEMail\":\"$EMAIL\",\"userPassword\":\"$PASSWORD\"}")
echo "status: $DUP_STATUS"
[ "$DUP_STATUS" = "409" ] || fail "expected 409 for duplicate email, got $DUP_STATUS"

line "7. Bad credentials on login (expect 401)"
BAD_LOGIN_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/login" \
  -H "Content-Type: application/json" \
  -d "{\"userMail\":\"$EMAIL\",\"userPassword\":\"wrongpassword\"}")
echo "status: $BAD_LOGIN_STATUS"
[ "$BAD_LOGIN_STATUS" = "401" ] || fail "expected 401 for bad credentials, got $BAD_LOGIN_STATUS"

line "8. Invalid registration payload (expect 400)"
INVALID_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/register" \
  -H "Content-Type: application/json" \
  -d '{"userName":"","userLastName":"Lovelace","userEMail":"not-an-email","userPassword":"short"}')
echo "status: $INVALID_STATUS"
[ "$INVALID_STATUS" = "400" ] || fail "expected 400 for invalid payload, got $INVALID_STATUS"

line "All checks passed"
