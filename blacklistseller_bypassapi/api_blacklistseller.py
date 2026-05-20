import sys
import time
import curl_cffi, requests
from bs4 import BeautifulSoup

sys.stdout.reconfigure(encoding="utf-8")

s = curl_cffi.Session(impersonate="chrome")
CAPSOLVE_APIKEY = "CAP-D5903264575AE0571295C9D7A21B097F"
headers = {
    'accept': 'application/json, text/javascript, */*; q=0.01',
    'accept-language': 'en-GB,en-US;q=0.9,en;q=0.8,th;q=0.7',
    'cache-control': 'no-cache',
    'pragma': 'no-cache',
    'priority': 'u=1, i',
    'referer': 'https://www.blacklistseller.com/report/report_search_page',
    'sec-ch-ua': '"Google Chrome";v="143", "Chromium";v="143", "Not A(Brand";v="24"',
    'sec-ch-ua-mobile': '?0',
    'sec-ch-ua-platform': '"Windows"',
    'sec-fetch-dest': 'empty',
    'sec-fetch-mode': 'cors',
    'sec-fetch-site': 'same-origin',
    'user-agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36',
    'x-requested-with': 'XMLHttpRequest',
}

phone_number = input("Enter Search Phonenumber: ")

def create_task():
    r = requests.post('https://api.capsolver.com/createTask', headers={
        'Content-Type': 'application/json'}, json={
            "clientKey": CAPSOLVE_APIKEY,
            "task": {
                "type": "AntiTurnstileTaskProxyLess",
                "websiteURL": "https://www.blacklistseller.com",
                "websiteKey": "0x4AAAAAAADldoGeFayMYGp_",
            }
        })
    return r.json()['taskId']

def get_result(task_id):
    while True:
        r = requests.post('https://api.capsolver.com/getTaskResult', headers={
            'Content-Type': 'application/json'}, json={
                "clientKey": CAPSOLVE_APIKEY,
                "taskId": task_id
            })
        res = r.json()
        if res['status'] == 'processing':
            time.sleep(1)
            continue
        if 'solution' not in res:
            raise RuntimeError(f"Capsolver error: {res}")
        return res['solution']['token']

def csrf_token():
    response = s.get('https://www.blacklistseller.com/home/refresh_csrf_token')
    data = response.json()
    return data['csrf_token']


login = s.post("https://www.blacklistseller.com/profile/check_login", headers=headers, data={"user": "67sixseven", "pass": "67766776_", "bls_csrf_token_name": csrf_token()})

# Visit the search page once to ensure the session is initialized correctly and reuse the CSRF token from the form.
search_page = s.get("https://www.blacklistseller.com/report/report_search_page", headers=headers)
search_soup = BeautifulSoup(search_page.text, "html.parser")
form_token_input = search_soup.find("input", {"name": "bls_csrf_token_name"})
form_token = form_token_input["value"] if form_token_input else csrf_token()

task_id = create_task()
token = get_result(task_id)
print("Turnstile token:", token[:30], "...")


resp2 = s.post("https://www.blacklistseller.com/report/report_search_success_page", headers=headers, data={
    "bls_csrf_token_name": form_token,
    "bank_number": phone_number,
    "first_name": "",
    "last_name": "",
    "idcard": "",
    "cf-turnstile-response": token
})

soup = BeautifulSoup(resp2.text, "html.parser")

# Bail out early if the site redirected us to the login page or structure changed.
if "Login Required" in resp2.text:
    raise SystemExit("ถูกบังคับให้ล็อกอินใหม่หรือเซสชันหมดอายุ (Login Required)")

table = soup.find("table", class_="modern-table")
if not table:
    raise SystemExit("ไม่เจอตารางผลลัพธ์ในหน้าที่ได้กลับมา")

rows_data = []

for row in table.find("tbody").find_all("tr"):
    cols = row.find_all("td")
    if len(cols) < 3:
        continue

    index = cols[0].get_text(strip=True)

    seller_info = cols[1].get_text(separator=" ", strip=True)

    amount = cols[2].get_text(strip=True)

    rows_data.append({
        "index": index,
        "seller_info": seller_info,
        "amount": amount
    })

for item in rows_data:
    print(item)
