"""
WHOOP sign-in (AWS Cognito behind WHOOP's auth-service/v3) with a stored refresh token.

- Daily runs use the saved refresh token (valid ~30 days): no code needed.
- When WHOOP asks for a verification code (email / SMS), the code is collected:
    * on GitHub Actions: the job opens an issue in the repo, @mentions the owner,
      and waits for the owner to reply with the code (only the repo owner's
      comments are accepted; the comment is deleted after use)
    * locally: a normal terminal prompt
- The refresh token is stored in data/raw/auth.json, which is only ever kept
  inside the encrypted vault (see vault.py), never in git.
"""

import json
import os
import re
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import requests

COGNITO_URL = "https://api.prod.whoop.com/auth-service/v3/whoop/"
COGNITO_UA = ("aws-sdk-swift/1.5.86 ua/2.1 api/cognito_identity_provider#1.5.86 "
              "os/ios#26.3.1 lang/swift#5.10 m/D,N,Z,b")
CODE_KEYS = {"EMAIL_OTP": "EMAIL_OTP_CODE", "SMS_MFA": "SMS_MFA_CODE",
             "SOFTWARE_TOKEN_MFA": "SOFTWARE_TOKEN_MFA_CODE"}
CHANNEL_AR = {"EMAIL_OTP": "إيميلك", "SMS_MFA": "جوالك برسالة SMS",
              "SOFTWARE_TOKEN_MFA": "تطبيق المصادقة"}


class LoginError(Exception):
    pass


def _log(msg):
    print(f"[{datetime.now():%H:%M:%S}] {msg}", flush=True)


# ───────────────────────── Cognito calls ─────────────────────────

def _cognito(target, body, email=""):
    try:
        r = requests.post(COGNITO_URL, timeout=30, json=body, headers={
            "content-type": "application/x-amz-json-1.1",
            "x-amz-target": f"AWSCognitoIdentityProviderService.{target}",
            "amz-sdk-request": "attempt=1; max=1",
            "amz-sdk-invocation-id": str(uuid.uuid4()),
            "user-agent": COGNITO_UA,
            "accept": "*/*",
            "accept-language": "en-US,en;q=0.9",
        })
    except Exception as e:
        raise LoginError(f"Could not reach WHOOP sign-in server: {type(e).__name__}")
    try:
        j = r.json()
    except ValueError:
        j = {}
    if r.status_code == 200:
        return j
    kind = (j.get("__type") or "").split("#")[-1]
    msg = (j.get("message") or r.text or "")[:200]
    if email:
        msg = msg.replace(email, "<email>")
    err = LoginError(f"HTTP {r.status_code} {kind}: {msg}")
    err.kind, err.msg = kind, msg
    raise err


def _initiate(email, pwd):
    return _cognito("InitiateAuth", {"AuthFlow": "USER_PASSWORD_AUTH", "ClientId": "",
                                     "AuthParameters": {"USERNAME": email, "PASSWORD": pwd}}, email)


def _respond(email, challenge, session, code):
    return _cognito("RespondToAuthChallenge", {
        "ClientId": "", "ChallengeName": challenge, "Session": session,
        "ChallengeResponses": {"USERNAME": email, CODE_KEYS[challenge]: code}}, email)


def _refresh(refresh_token):
    return _cognito("InitiateAuth", {"AuthFlow": "REFRESH_TOKEN_AUTH", "ClientId": "",
                                     "AuthParameters": {"REFRESH_TOKEN": refresh_token}})


# ───────────────────────── code collection ─────────────────────────

class GitHubCodeBox:
    """Asks the repo owner for the code through a GitHub issue and waits for the reply."""

    def __init__(self):
        self.repo = os.environ["GITHUB_REPOSITORY"]
        self.owner = os.getenv("GITHUB_REPOSITORY_OWNER") or self.repo.split("/")[0]
        self.h = {"Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
                  "Accept": "application/vnd.github+json"}
        self.api = f"https://api.github.com/repos/{self.repo}"
        self.issue = None

    def _open_issue(self):
        if self.issue:
            return
        r = requests.get(f"{self.api}/issues", headers=self.h, params={"state": "open", "labels": "whoop-login"}, timeout=30)
        found = r.json() if r.ok else []
        if found:
            self.issue = found[0]["number"]
            return
        requests.post(f"{self.api}/labels", headers=self.h, timeout=30,
                      json={"name": "whoop-login", "color": "10B981"})
        r = requests.post(f"{self.api}/issues", headers=self.h, timeout=30, json={
            "title": "🔑 WHOOP يحتاج رمز التحقق",
            "labels": ["whoop-login"],
            "body": (f"@{self.owner}\n\nWHOOP يطلب رمز تحقق عشان يكمل السحب التلقائي.\n\n"
                     "رد على هذي الصفحة بالرمز فقط (6 أرقام) وأكمل أنا الباقي.\n\n"
                     "الرمز ينحذف تلقائياً بعد استخدامه وهذي الصفحة تنقفل. "
                     "بتحتاج تسوي هذا تقريباً مرة كل شهر.")})
        r.raise_for_status()
        self.issue = r.json()["number"]

    def say(self, text):
        self._open_issue()
        requests.post(f"{self.api}/issues/{self.issue}/comments", headers=self.h, timeout=30,
                      json={"body": text})

    def wait_for(self, pattern, since, timeout_s):
        """Return (text, comment_id) of the first owner comment after `since` matching pattern."""
        self._open_issue()
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            r = requests.get(f"{self.api}/issues/{self.issue}/comments", headers=self.h, timeout=30,
                             params={"since": since, "per_page": 100})
            if r.ok:
                for c in r.json():
                    if (c.get("user") or {}).get("login", "").lower() != self.owner.lower():
                        continue
                    if c["created_at"] < since:
                        continue
                    m = re.search(pattern, c.get("body") or "")
                    if m:
                        return m.group(0), c["id"]
            time.sleep(5)
        return None, None

    def delete_comment(self, cid):
        requests.delete(f"{self.api}/issues/comments/{cid}", headers=self.h, timeout=30)

    def close(self, text):
        if not self.issue:
            return
        self.say(text)
        requests.patch(f"{self.api}/issues/{self.issue}", headers=self.h, timeout=30, json={"state": "closed"})


def _now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _login_with_code(email, pwd):
    """Full sign-in, answering the verification-code challenge if WHOOP asks for one."""
    on_gh = bool(os.getenv("GITHUB_ACTIONS") and os.getenv("GITHUB_TOKEN"))
    box = GitHubCodeBox() if on_gh else None

    # A scheduled run may fire while the owner is busy: ask first, send the code only when they're ready.
    if box and os.getenv("GITHUB_EVENT_NAME") == "schedule":
        since = _now_iso()
        box.say(f"@{box.owner} رد بكلمة **جاهز** وقت ما تكون فاضي وأرسل لك رمز WHOOP. أنتظر لين 45 دقيقة.")
        _log("Waiting for the owner to reply 'جاهز' on the login issue...")
        ok, _ = box.wait_for(r"جاهز|ready", since, 45 * 60)
        if not ok:
            raise LoginError("WHOOP needs a verification code and nobody replied on the login issue in time. "
                             "Reply on the open 'WHOOP' issue or run the workflow manually")

    for attempt in range(3):
        res = _initiate(email, pwd)
        if res.get("AuthenticationResult"):
            return res["AuthenticationResult"]
        challenge = res.get("ChallengeName")
        if challenge not in CODE_KEYS:
            raise LoginError(f"Unexpected WHOOP sign-in step: {challenge}")
        session = res.get("Session")
        where = CHANNEL_AR[challenge]

        for tries in range(3):
            if box:
                since = _now_iso()
                if tries == 0:
                    box.say(f"@{box.owner} أرسلت WHOOP رمز على {where}. رد هنا بالرمز خلال 3 دقايق.")
                _log(f"Verification code sent ({challenge}); waiting for the reply on issue #{box.issue}...")
                code, cid = box.wait_for(r"\b\d{6}\b", since, 5 * 60)
                if not code:
                    raise LoginError("No verification code received on the login issue within 5 minutes")
                box.delete_comment(cid)
            else:
                code = input(f"WHOOP sent a code to your {challenge.split('_')[0].lower()}. Enter it: ").strip()
            try:
                out = _respond(email, challenge, session, code)
            except LoginError as e:
                if getattr(e, "kind", "") == "CodeMismatchException":
                    if box:
                        box.say("الرمز غير صحيح. تأكد منه وأرسله مرة ثانية.")
                    continue
                if "session" in getattr(e, "msg", "").lower() or getattr(e, "kind", "") == "ExpiredCodeException":
                    if box:
                        box.say("انتهت صلاحية الرمز. بأرسل لك رمز جديد الحين.")
                    break          # start a fresh sign-in → new code
                raise
            if out.get("AuthenticationResult"):
                if box:
                    box.close("✓ تم. رجع السحب التلقائي يشتغل وبيطلب منك رمز جديد بعد تقريباً شهر.")
                return out["AuthenticationResult"]
            session = out.get("Session", session)
        # fell through: session expired or too many wrong codes → new attempt
    raise LoginError("Could not complete WHOOP verification after 3 attempts")


# ───────────────────────── public entry ─────────────────────────

def get_tokens(email, pwd, store: Path):
    """Return (access_token, refresh_token). Uses the saved refresh token when possible."""
    saved = {}
    if store.exists():
        try:
            saved = json.loads(store.read_text())
        except ValueError:
            saved = {}
    rt = saved.get("refresh_token") if saved.get("email") == email else None

    if rt:
        try:
            ar = _refresh(rt)["AuthenticationResult"]
            _log("Signed in with saved token ✓")
            return ar["AccessToken"], ar.get("RefreshToken") or rt
        except (LoginError, KeyError) as e:
            _log(f"Saved token no longer valid ({str(e)[:80]}) → full sign-in")

    try:
        ar = _login_with_code(email, pwd)
    except LoginError as e:
        kind = getattr(e, "kind", "")
        hint = {"NotAuthorizedException": "Wrong WHOOP email or password",
                "UserNotFoundException": "No WHOOP account with this email",
                "PasswordResetRequiredException": "WHOOP requires a password reset",
                "TooManyRequestsException": "Too many sign-in attempts; wait and retry later"}.get(kind)
        raise LoginError(f"{hint} ({e})" if hint else str(e))

    new_rt = ar.get("RefreshToken") or rt
    store.parent.mkdir(parents=True, exist_ok=True)
    store.write_text(json.dumps({"email": email, "refresh_token": new_rt,
                                 "saved": datetime.now().strftime("%Y-%m-%d")}))
    try:
        os.chmod(store, 0o600)
    except OSError:
        pass
    _log("Signed in ✓ (token saved)")
    return ar["AccessToken"], new_rt
