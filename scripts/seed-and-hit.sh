#!/usr/bin/env bash
#
# seed-and-hit.sh — seeds data into the shop API and then exercises every route.
#
# Usage:
#   ./seed-and-hit.sh                       # defaults to http://localhost:8080
#   BASE_URL=http://host:8080 ./seed-and-hit.sh
#   ./seed-and-hit.sh https://host:8443     # positional base URL
#
# Requires: curl, jq
#
set -uo pipefail

BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
PASS=0
FAIL=0

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq is required (apt-get install jq)"; exit 1; }

c() { printf '\033[%sm%s\033[0m' "$1" "$2"; }
hr() { printf '%.0s─' {1..70}; echo; }
section() { hr; echo "$(c '1;36' "▶ $1")"; hr; }

# req METHOD PATH [JSON_BODY] [EXPECTED_STATUS]
# Prints a PASS/FAIL line and echoes the response body on stdout (for capturing IDs).
req() {
  local method="$1" path="$2" body="${3:-}" expect="${4:-}"
  local url="${BASE_URL}${path}" tmp code
  tmp="$(mktemp)"
  if [[ -n "$body" ]]; then
    code="$(curl -sk -o "$tmp" -w '%{http_code}' -X "$method" "$url" \
      -H 'Content-Type: application/json' -d "$body")"
  else
    code="$(curl -sk -o "$tmp" -w '%{http_code}' -X "$method" "$url")"
  fi
  local ok=1
  if [[ -n "$expect" ]]; then
    [[ "$code" == "$expect" ]] || ok=0
  else
    [[ "$code" =~ ^2 ]] || ok=0
  fi
  if [[ $ok -eq 1 ]]; then
    PASS=$((PASS+1)); printf '  %s %-6s %s\n' "$(c '1;32' 'PASS')" "$method" "$path ($code)" >&2
  else
    FAIL=$((FAIL+1)); printf '  %s %-6s %s\n' "$(c '1;31' 'FAIL')" "$method" "$path (got $code, want ${expect:-2xx})" >&2
    head -c 300 "$tmp" >&2; echo >&2
  fi
  cat "$tmp"; rm -f "$tmp"
}

jqid() { jq -r "${2:-.id}" <<<"$1"; }

echo "Target: $(c '1;33' "$BASE_URL")"

########################################################################
section "0. Health checks"
########################################################################
req GET /health >/dev/null
req GET /api/health/db >/dev/null
req GET /actuator/health >/dev/null

########################################################################
section "1. Seed categories"
########################################################################
CAT_ELECTRONICS=$(jqid "$(req POST /api/categories '{"slug":"electronics","name":"Electronics","description":"Gadgets and devices","active":true}')")
CAT_BOOKS=$(jqid "$(req POST /api/categories '{"slug":"books","name":"Books","description":"Printed and digital books","active":true}')")
CAT_APPAREL=$(jqid "$(req POST /api/categories '{"slug":"apparel","name":"Apparel","description":"Clothing and accessories","active":true}')")
# child category
req POST /api/categories "{\"slug\":\"laptops\",\"name\":\"Laptops\",\"parentId\":\"$CAT_ELECTRONICS\",\"active\":true}" >/dev/null
# duplicate slug -> 409
req POST /api/categories '{"slug":"electronics","name":"dupe"}' 409 >/dev/null
echo "  seeded categories: electronics=$CAT_ELECTRONICS books=$CAT_BOOKS apparel=$CAT_APPAREL"

########################################################################
section "2. Seed products"
########################################################################
declare -a PRODUCT_IDS=()
seed_product() { # sku name categoryId price stock tag
  local resp; resp="$(req POST /api/products \
    "{\"sku\":\"$1\",\"name\":\"$2\",\"description\":\"$2 — great value\",\"categoryId\":\"$3\",\"price\":$4,\"stock\":$5,\"tags\":[\"$6\"],\"attributes\":{\"warranty\":\"1y\"}}")"
  PRODUCT_IDS+=("$(jqid "$resp")")
}
seed_product "SKU-LAP-1"  "UltraBook 14"      "$CAT_ELECTRONICS" 1299.99 25 "featured"
seed_product "SKU-PHN-1"  "Smartphone X"      "$CAT_ELECTRONICS"  899.00 40 "featured"
seed_product "SKU-HDP-1"  "Noise Headphones"  "$CAT_ELECTRONICS"  199.50 100 "audio"
seed_product "SKU-BOK-1"  "Clean Code"        "$CAT_BOOKS"         34.99 200 "bestseller"
seed_product "SKU-BOK-2"  "The Pragmatic Dev" "$CAT_BOOKS"         42.00 150 "bestseller"
seed_product "SKU-SHR-1"  "Cotton T-Shirt"    "$CAT_APPAREL"       19.99 300 "summer"
seed_product "SKU-JKT-1"  "Winter Jacket"     "$CAT_APPAREL"      129.00  60 "winter"
# duplicate sku -> 409
req POST /api/products '{"sku":"SKU-LAP-1","name":"dupe","price":1}' 409 >/dev/null
echo "  seeded ${#PRODUCT_IDS[@]} products"

########################################################################
section "3. Seed users"
########################################################################
declare -a USER_IDS=()
seed_user() { # email name role
  local resp; resp="$(req POST /api/users \
    "{\"email\":\"$1\",\"fullName\":\"$2\",\"phone\":\"+1-555-0100\",\"role\":\"$3\",\"addresses\":[{\"label\":\"home\",\"line1\":\"1 Main St\",\"city\":\"Metropolis\",\"country\":\"US\",\"zip\":\"10001\",\"primary\":true}],\"tags\":[\"seed\"]}")"
  USER_IDS+=("$(jqid "$resp")")
}
seed_user "alice@example.com" "Alice Adams"   "CUSTOMER"
seed_user "bob@example.com"   "Bob Baker"     "CUSTOMER"
seed_user "carol@example.com" "Carol Chen"    "SELLER"
seed_user "dave@example.com"  "Dave Diaz"     "ADMIN"
# duplicate email -> 409, missing email -> 400
req POST /api/users '{"email":"alice@example.com","fullName":"dupe"}' 409 >/dev/null
req POST /api/users '{"fullName":"no email"}' 400 >/dev/null
echo "  seeded ${#USER_IDS[@]} users"

########################################################################
section "4. Seed orders"
########################################################################
declare -a ORDER_IDS=()
place_order() { # userId productId qty
  local resp; resp="$(req POST /api/orders \
    "{\"userId\":\"$1\",\"paymentMethod\":\"CARD\",\"shippingAddress\":\"1 Main St, Metropolis\",\"items\":[{\"productId\":\"$2\",\"quantity\":$3}]}")"
  ORDER_IDS+=("$(jqid "$resp")")
}
place_order "${USER_IDS[0]}" "${PRODUCT_IDS[0]}" 1
place_order "${USER_IDS[0]}" "${PRODUCT_IDS[3]}" 2
place_order "${USER_IDS[1]}" "${PRODUCT_IDS[2]}" 3
# multi-line order
req POST /api/orders "{\"userId\":\"${USER_IDS[1]}\",\"items\":[{\"productId\":\"${PRODUCT_IDS[4]}\",\"quantity\":1},{\"productId\":\"${PRODUCT_IDS[5]}\",\"quantity\":2}]}" >/dev/null
# bad orders
req POST /api/orders "{\"userId\":\"${USER_IDS[0]}\",\"items\":[]}" 400 >/dev/null
req POST /api/orders '{"userId":"nope","items":[{"productId":"x","quantity":1}]}' 400 >/dev/null
echo "  placed ${#ORDER_IDS[@]} orders (+extras)"

########################################################################
section "5. Seed reviews"
########################################################################
declare -a REVIEW_IDS=()
add_review() { # productId userId rating title
  local resp; resp="$(req POST /api/reviews \
    "{\"productId\":\"$1\",\"userId\":\"$2\",\"rating\":$3,\"title\":\"$4\",\"body\":\"Detailed thoughts about the product.\",\"verifiedPurchase\":true}")"
  REVIEW_IDS+=("$(jqid "$resp")")
}
add_review "${PRODUCT_IDS[0]}" "${USER_IDS[0]}" 5 "Excellent"
add_review "${PRODUCT_IDS[0]}" "${USER_IDS[1]}" 4 "Very good"
add_review "${PRODUCT_IDS[3]}" "${USER_IDS[0]}" 5 "A classic"
# invalid rating -> 400
req POST /api/reviews "{\"productId\":\"${PRODUCT_IDS[0]}\",\"userId\":\"${USER_IDS[0]}\",\"rating\":9}" 400 >/dev/null
echo "  seeded ${#REVIEW_IDS[@]} reviews"

########################################################################
section "6. Read / list / filter / paginate / search"
########################################################################
req GET "/api/categories" >/dev/null
req GET "/api/categories?active=true" >/dev/null
req GET "/api/categories/$CAT_ELECTRONICS" >/dev/null
req GET "/api/categories/slug/books" >/dev/null

req GET "/api/products?page=0&size=5&sort=price&dir=asc" >/dev/null
req GET "/api/products?categoryId=$CAT_ELECTRONICS" >/dev/null
req GET "/api/products?minPrice=20&maxPrice=200" >/dev/null
req GET "/api/products?tag=featured" >/dev/null
req GET "/api/products?active=true" >/dev/null
req GET "/api/products/search?q=book" >/dev/null
req GET "/api/products/${PRODUCT_IDS[0]}" >/dev/null
req GET "/api/products/sku/SKU-LAP-1" >/dev/null
req GET "/api/products/${PRODUCT_IDS[0]}/reviews" >/dev/null

req GET "/api/users?page=0&size=10" >/dev/null
req GET "/api/users?role=CUSTOMER" >/dev/null
req GET "/api/users?status=ACTIVE" >/dev/null
req GET "/api/users/search?q=alice" >/dev/null
req GET "/api/users/count" >/dev/null
req GET "/api/users/count?status=ACTIVE" >/dev/null
req GET "/api/users/${USER_IDS[0]}" >/dev/null
req GET "/api/users/by-email?email=bob@example.com" >/dev/null

req GET "/api/orders?page=0&size=10" >/dev/null
req GET "/api/orders?status=PENDING" >/dev/null
req GET "/api/orders/count" >/dev/null
req GET "/api/orders/user/${USER_IDS[0]}" >/dev/null
req GET "/api/orders/${ORDER_IDS[0]}" >/dev/null

req GET "/api/reviews?productId=${PRODUCT_IDS[0]}" >/dev/null
req GET "/api/reviews?userId=${USER_IDS[0]}" >/dev/null
req GET "/api/reviews/${REVIEW_IDS[0]}" >/dev/null

########################################################################
section "7. Update / patch / status transitions"
########################################################################
req PATCH "/api/users/${USER_IDS[0]}" '{"fullName":"Alice A. Adams","phone":"+1-555-0199"}' >/dev/null
req PUT   "/api/categories/$CAT_APPAREL" '{"slug":"apparel","name":"Apparel & Accessories","active":true}' >/dev/null
req PATCH "/api/products/${PRODUCT_IDS[1]}" '{"price":849.00,"name":"Smartphone X (2026)"}' >/dev/null
req PATCH "/api/products/${PRODUCT_IDS[1]}/stock" '{"delta":-5}' >/dev/null
req PATCH "/api/products/${PRODUCT_IDS[2]}/stock" '{"set":500}' >/dev/null
req PATCH "/api/orders/${ORDER_IDS[0]}/status" '{"status":"PAID"}' >/dev/null
req PATCH "/api/orders/${ORDER_IDS[1]}/status" '{"status":"SHIPPED"}' >/dev/null
req PATCH "/api/orders/${ORDER_IDS[0]}/status" '{"status":"BOGUS"}' 400 >/dev/null
req POST  "/api/orders/${ORDER_IDS[2]}/cancel" >/dev/null
req POST  "/api/reviews/${REVIEW_IDS[0]}/helpful" >/dev/null

########################################################################
section "8. Not-found & error paths"
########################################################################
req GET    "/api/users/deadbeefdeadbeefdeadbeef" '' 404 >/dev/null
req GET    "/api/products/deadbeefdeadbeefdeadbeef" '' 404 >/dev/null
req GET    "/api/orders/deadbeefdeadbeefdeadbeef" '' 404 >/dev/null
req DELETE "/api/users/deadbeefdeadbeefdeadbeef" '' 404 >/dev/null

########################################################################
section "9. Delete (cleanup of a subset)"
########################################################################
req DELETE "/api/reviews/${REVIEW_IDS[1]}" '' 204 >/dev/null
# create-then-delete a throwaway product
THROWAWAY=$(jqid "$(req POST /api/products '{"sku":"SKU-TMP-1","name":"Temp","price":1.00,"stock":1}')")
req DELETE "/api/products/$THROWAWAY" '' 204 >/dev/null
req GET    "/api/products/$THROWAWAY" '' 404 >/dev/null

########################################################################
hr
TOTAL=$((PASS+FAIL))
echo "$(c '1;36' 'SUMMARY'): $(c '1;32' "$PASS passed"), $(c '1;31' "$FAIL failed") of $TOTAL checks"
hr
[[ $FAIL -eq 0 ]]
