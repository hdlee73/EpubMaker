"""업데이트 서명용 ECDSA(P-256) 키 한 쌍을 만든다. (최초 1회, 본인 PC에서 실행)

사용법:  pip install cryptography  →  python tools/gen_keys.py

출력되는 두 값을 다음처럼 쓴다.
  1) PUBLIC  → app/src/main/java/com/hdlee/epubmaker/update/Updater.kt 의 PUBLIC_KEY_B64 에
               붙여 넣고 커밋 (공개되어도 안전)
  2) PRIVATE → GitHub 저장소 Settings > Secrets and variables > Actions 에
               이름 UPDATE_SIGNING_KEY 로 등록 (-----BEGIN ... END----- 줄 포함 전체).
               파일/저장소/채팅에 절대 남기지 말 것.
"""

import base64

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

key = ec.generate_private_key(ec.SECP256R1())
private_pem = key.private_bytes(
    serialization.Encoding.PEM,
    serialization.PrivateFormat.PKCS8,
    serialization.NoEncryption(),
).decode()
public_der = key.public_key().public_bytes(
    serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
)

print("PUBLIC  (Updater.kt 의 PUBLIC_KEY_B64):")
print(base64.b64encode(public_der).decode())
print()
print("PRIVATE (GitHub Secret UPDATE_SIGNING_KEY — 비밀로 보관):")
print(private_pem)
