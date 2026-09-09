#!/usr/bin/env bash
# Smoke-test the auth, car, group and trip endpoints against a running API.
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

# --- Cars -------------------------------------------------------------------
# Seeded by V2__cars_and_models.sql. Fixed ids, so this is stable across runs.
CARS="$BASE_URL/api/v1/cars"
MODEL_GOL="00000000-0000-4000-8000-000000000001"
UNKNOWN_MODEL="00000000-0000-4000-8000-0000000000ff"
PLATE="AB$(date +%s | tail -c 6)"

# Register a second, clean user to own the cars - the one above was logged out
# in step 4, and its refresh family is revoked.
CAR_EMAIL="carl+$(date +%s)@example.com"
CAR_REG=$(curl -sS -X POST "$BASE/register" \
  -H "Content-Type: application/json" \
  -d "{\"userName\":\"Carl\",\"userLastName\":\"Sagan\",\"userEMail\":\"$CAR_EMAIL\",\"userPassword\":\"$PASSWORD\"}")
CAR_TOKEN=$(extract_field "$CAR_REG" accessToken)
[ -n "$CAR_TOKEN" ] || fail "could not register a user to own cars"
# Kept so the trip checks below can assert the driver really is this account.
CAR_USER_ID=$(extract_field "$CAR_REG" userId)

line "9. Create car (expect 201 + Location)"
CREATE_HEADERS=$(mktemp); trap 'rm -f "$JAR" "$CREATE_HEADERS"' EXIT
CAR_BODY=$(curl -sS -D "$CREATE_HEADERS" -X POST "$CARS" \
  -H "Authorization: Bearer $CAR_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"Ada Gol\",
    \"licensePlate\": \"${PLATE}\",
    \"modelId\": \"$MODEL_GOL\",
    \"mileage\": 120000
  }")
print_json "$CAR_BODY"
grep -qi "^HTTP/1.1 201" "$CREATE_HEADERS" || fail "expected 201 from create car"
CAR_ID=$(extract_field "$CAR_BODY" id)
[ -n "$CAR_ID" ] || fail "no id in create-car response"
grep -qi "^location:.*/api/v1/cars/$CAR_ID" "$CREATE_HEADERS" \
  || fail "Location header missing or does not point at the new car"
# The model is resolved from the catalog and echoed back, not taken on trust.
echo "$CAR_BODY" | grep -q '"brand"[[:space:]]*:[[:space:]]*"Volkswagen"' \
  || fail "expected the seeded Volkswagen Gol in the response"
# A car that has never reported telemetry has an empty snapshot.
echo "$CAR_BODY" | grep -q '"fuelLevel"[[:space:]]*:[[:space:]]*null' \
  || fail "expected fuelLevel to be null on a fresh car"

line "10. Plate is normalised to upper case"
LOWER_PLATE=$(echo "$PLATE" | tr '[:upper:]' '[:lower:]')
NORM_BODY=$(curl -sS -X POST "$CARS" \
  -H "Authorization: Bearer $CAR_TOKEN" -H "Content-Type: application/json" \
  -d "{\"name\":\"Normalise me\",\"licensePlate\":\"${LOWER_PLATE}x\",\"modelId\":\"$MODEL_GOL\"}")
echo "$NORM_BODY" | grep -q "\"licensePlate\"[[:space:]]*:[[:space:]]*\"${PLATE}X\"" \
  || fail "expected plate to be stored upper-cased"
echo "ok: ${LOWER_PLATE}x -> ${PLATE}X"

line "11. Create car without a token (expect 401)"
NOAUTH_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$CARS" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"No auth\",\"modelId\":\"$MODEL_GOL\"}")
echo "status: $NOAUTH_STATUS"
[ "$NOAUTH_STATUS" = "401" ] || fail "expected 401 without a token, got $NOAUTH_STATUS"

line "12. Unknown model id (expect 404)"
UNKNOWN_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$CARS" \
  -H "Authorization: Bearer $CAR_TOKEN" -H "Content-Type: application/json" \
  -d "{\"name\":\"Ghost\",\"modelId\":\"$UNKNOWN_MODEL\"}")
echo "status: $UNKNOWN_STATUS"
[ "$UNKNOWN_STATUS" = "404" ] || fail "expected 404 for unknown model, got $UNKNOWN_STATUS"

line "13. Same plate twice for one owner (expect 409)"
DUP_PLATE_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$CARS" \
  -H "Authorization: Bearer $CAR_TOKEN" -H "Content-Type: application/json" \
  -d "{\"name\":\"Same plate\",\"licensePlate\":\"${PLATE}\",\"modelId\":\"$MODEL_GOL\"}")
echo "status: $DUP_PLATE_STATUS"
[ "$DUP_PLATE_STATUS" = "409" ] || fail "expected 409 for duplicate plate, got $DUP_PLATE_STATUS"

line "14. Invalid car payload (expect 400)"
BAD_CAR_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$CARS" \
  -H "Authorization: Bearer $CAR_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"  ","mileage":-5}')
echo "status: $BAD_CAR_STATUS"
[ "$BAD_CAR_STATUS" = "400" ] || fail "expected 400 for invalid car payload, got $BAD_CAR_STATUS"

line "15. Several cars with no plate are allowed (expect 201, 201)"
for n in 1 2; do
  PLATELESS_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$CARS" \
    -H "Authorization: Bearer $CAR_TOKEN" -H "Content-Type: application/json" \
    -d "{\"name\":\"Plateless $n\",\"modelId\":\"$MODEL_GOL\"}")
  echo "car $n status: $PLATELESS_STATUS"
  [ "$PLATELESS_STATUS" = "201" ] || fail "expected 201 for plateless car $n, got $PLATELESS_STATUS"
done

line "16. A different owner may register the same plate (expect 201)"
OTHER_EMAIL="grace+$(date +%s)@example.com"
OTHER_REG=$(curl -sS -X POST "$BASE/register" -H "Content-Type: application/json" \
  -d "{\"userName\":\"Grace\",\"userLastName\":\"Hopper\",\"userEMail\":\"$OTHER_EMAIL\",\"userPassword\":\"$PASSWORD\"}")
OTHER_TOKEN=$(extract_field "$OTHER_REG" accessToken)
[ -n "$OTHER_TOKEN" ] || fail "could not register the second owner"
OTHER_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$CARS" \
  -H "Authorization: Bearer $OTHER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"name\":\"Same plate, other owner\",\"licensePlate\":\"${PLATE}\",\"modelId\":\"$MODEL_GOL\"}")
echo "status: $OTHER_STATUS"
[ "$OTHER_STATUS" = "201" ] || fail "plates must be unique per owner, not globally; got $OTHER_STATUS"

line "17. ownerId in the body is ignored (expect 201)"
# The owner always comes from the access token. A body that names someone else
# must not be rejected *or* honoured - the field is simply not part of the API.
SPOOF_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$CARS" \
  -H "Authorization: Bearer $CAR_TOKEN" -H "Content-Type: application/json" \
  -d "{\"name\":\"Spoof attempt\",\"modelId\":\"$MODEL_GOL\",\"ownerId\":\"99999999-9999-9999-9999-999999999999\"}")
echo "status: $SPOOF_STATUS"
[ "$SPOOF_STATUS" = "201" ] || fail "expected 201 with a stray ownerId, got $SPOOF_STATUS"

# --- Groups -----------------------------------------------------------------
# Not named GROUPS: that is a bash special variable holding the caller's
# group ids, so assigning it is ignored and "$GROUPS_URL" expands to a number.
GROUPS_URL="$BASE_URL/api/v1/groups"

line "18. Create group (expect 201 + Location)"
GROUP_HEADERS=$(mktemp); trap 'rm -f "$JAR" "$CREATE_HEADERS" "$GROUP_HEADERS"' EXIT
GROUP_BODY=$(curl -sS -D "$GROUP_HEADERS" -X POST "$GROUPS_URL" \
  -H "Authorization: Bearer $CAR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "Familia Lazzari"}')
print_json "$GROUP_BODY"
grep -qi "^HTTP/1.1 201" "$GROUP_HEADERS" || fail "expected 201 from create group"
GROUP_ID=$(extract_field "$GROUP_BODY" id)
[ -n "$GROUP_ID" ] || fail "no id in create-group response"
grep -qi "^location:.*/api/v1/groups/$GROUP_ID" "$GROUP_HEADERS" \
  || fail "Location header missing or does not point at the new group"
# The creator is enrolled as ADMIN, so the group starts with exactly one member.
echo "$GROUP_BODY" | grep -q '"memberCount"[[:space:]]*:[[:space:]]*1' \
  || fail "expected memberCount 1 on a new group"
echo "$GROUP_BODY" | grep -q '"callerRole"[[:space:]]*:[[:space:]]*"ADMIN"' \
  || fail "expected the creator to be ADMIN"

line "19. Create group without a token (expect 401)"
G_NOAUTH=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$GROUPS_URL" \
  -H "Content-Type: application/json" -d '{"name":"No auth"}')
echo "status: $G_NOAUTH"
[ "$G_NOAUTH" = "401" ] || fail "expected 401 without a token, got $G_NOAUTH"

line "20. Blank group name (expect 400)"
G_BLANK=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$GROUPS_URL" \
  -H "Authorization: Bearer $CAR_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"   "}')
echo "status: $G_BLANK"
[ "$G_BLANK" = "400" ] || fail "expected 400 for a blank group name, got $G_BLANK"

line "21. Two groups may share a name (expect 201)"
G_DUP=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$GROUPS_URL" \
  -H "Authorization: Bearer $OTHER_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Familia Lazzari"}')
echo "status: $G_DUP"
[ "$G_DUP" = "201" ] || fail "group names are labels, not identifiers; got $G_DUP"

line "22. creatorId in the body is ignored (expect 201)"
G_SPOOF=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$GROUPS_URL" \
  -H "Authorization: Bearer $CAR_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Spoof attempt","creatorId":"99999999-9999-9999-9999-999999999999"}')
echo "status: $G_SPOOF"
[ "$G_SPOOF" = "201" ] || fail "expected 201 with a stray creatorId, got $G_SPOOF"

# --- Trips ------------------------------------------------------------------
TRIPS="$BASE_URL/api/v1/trips"

line "23. Start a trip (expect 201 + Location)"
TRIP_HEADERS=$(mktemp)
trap 'rm -f "$JAR" "$CREATE_HEADERS" "$GROUP_HEADERS" "$TRIP_HEADERS"' EXIT
TRIP_BODY=$(curl -sS -D "$TRIP_HEADERS" -X POST "$TRIPS" \
  -H "Authorization: Bearer $CAR_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"carId\": \"$CAR_ID\", \"initialFuel\": 70}")
print_json "$TRIP_BODY"
grep -qi "^HTTP/1.1 201" "$TRIP_HEADERS" || fail "expected 201 from start trip"
TRIP_ID=$(extract_field "$TRIP_BODY" id)
[ -n "$TRIP_ID" ] || fail "no id in start-trip response"
grep -qi "^location:.*/api/v1/trips/$TRIP_ID" "$TRIP_HEADERS" \
  || fail "Location header missing or does not point at the new trip"
# The driver is the token holder, and the trip is left open - that open row is
# what "this car is in use right now" means.
[ "$(extract_field "$TRIP_BODY" driverId)" = "$CAR_USER_ID" ] \
  || fail "the trip's driver must be the authenticated caller"
echo "$TRIP_BODY" | grep -q '"active"[[:space:]]*:[[:space:]]*true' \
  || fail "expected a freshly started trip to be active"
# Consumption is derived from both readings, so it stays unknown until the
# trip is finished - it is never stored as a field of its own.
echo "$TRIP_BODY" | grep -q '"fuelUsed"[[:space:]]*:[[:space:]]*null' \
  || fail "expected fuelUsed to be null while the trip is running"

line "24. Second trip on a car already in use (expect 409)"
BUSY_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$TRIPS" \
  -H "Authorization: Bearer $CAR_TOKEN" -H "Content-Type: application/json" \
  -d "{\"carId\": \"$CAR_ID\"}")
echo "status: $BUSY_STATUS"
[ "$BUSY_STATUS" = "409" ] || fail "only one trip may be active per car, got $BUSY_STATUS"

line "25. Start a trip on someone else's car (expect 404, not 403)"
# 403 would confirm the id exists; not-mine and not-there must look identical.
FOREIGN_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$TRIPS" \
  -H "Authorization: Bearer $OTHER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"carId\": \"$CAR_ID\"}")
echo "status: $FOREIGN_STATUS"
[ "$FOREIGN_STATUS" = "404" ] || fail "expected 404 for another owner's car, got $FOREIGN_STATUS"

line "26. Start a trip on an unknown car (expect 404)"
GHOST_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$TRIPS" \
  -H "Authorization: Bearer $CAR_TOKEN" -H "Content-Type: application/json" \
  -d '{"carId": "00000000-0000-4000-8000-0000000000ff"}')
echo "status: $GHOST_STATUS"
[ "$GHOST_STATUS" = "404" ] || fail "expected 404 for an unknown car, got $GHOST_STATUS"

line "27. Start a trip without a token (expect 401)"
T_NOAUTH=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$TRIPS" \
  -H "Content-Type: application/json" -d "{\"carId\": \"$CAR_ID\"}")
echo "status: $T_NOAUTH"
[ "$T_NOAUTH" = "401" ] || fail "expected 401 without a token, got $T_NOAUTH"

line "28. Invalid trip payload (expect 400)"
T_BAD=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$TRIPS" \
  -H "Authorization: Bearer $CAR_TOKEN" -H "Content-Type: application/json" \
  -d '{"initialFuel": -5}')
echo "status: $T_BAD"
[ "$T_BAD" = "400" ] || fail "expected 400 for an invalid trip payload, got $T_BAD"

line "29. driverId in the body is ignored (expect 201)"
FRESH_CAR=$(curl -sS -X POST "$CARS" \
  -H "Authorization: Bearer $CAR_TOKEN" -H "Content-Type: application/json" \
  -d "{\"name\":\"Trip spoof car\",\"modelId\":\"$MODEL_GOL\"}")
FRESH_CAR_ID=$(extract_field "$FRESH_CAR" id)
[ -n "$FRESH_CAR_ID" ] || fail "could not create a second car for the spoof check"
SPOOF_TRIP=$(curl -sS -X POST "$TRIPS" \
  -H "Authorization: Bearer $CAR_TOKEN" -H "Content-Type: application/json" \
  -d "{\"carId\":\"$FRESH_CAR_ID\",\"driverId\":\"99999999-9999-9999-9999-999999999999\"}")
[ "$(extract_field "$SPOOF_TRIP" driverId)" = "$CAR_USER_ID" ] \
  || fail "driverId in the body must be ignored, not honoured"
echo "ok: driver is still $CAR_USER_ID"
# No initialFuel sent and the car has never reported, so there is nothing to
# fall back to - an honest null rather than a made-up zero.
echo "$SPOOF_TRIP" | grep -q '"initialFuel"[[:space:]]*:[[:space:]]*null' \
  || fail "expected initialFuel to be null when neither the client nor the car has a reading"

line "All checks passed"
