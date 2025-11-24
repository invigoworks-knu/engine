# 업비트 실거래 연동 가이드

업비트 API를 사용하여 실거래를 연동하는 방법을 안내합니다.

## 📋 목차

1. [업비트 API 키 발급](#1-업비트-api-키-발급)
2. [환경변수 설정](#2-환경변수-설정)
3. [API 연결 테스트](#3-api-연결-테스트)
4. [사용 가능한 API 엔드포인트](#4-사용-가능한-api-엔드포인트)
5. [보안 주의사항](#5-보안-주의사항)

---

## 1. 업비트 API 키 발급

### 1.1 업비트 웹사이트 로그인

1. 업비트 웹사이트 접속: https://upbit.com
2. 개인 계정으로 로그인

### 1.2 Open API 관리 페이지 이동

1. 우측 상단 **내 정보** 클릭
2. **Open API 관리** 메뉴 선택

### 1.3 API 키 발급

1. **"Open API Key 발급"** 버튼 클릭
2. **권한 설정** (체크박스):
   - ✅ **자산 조회** (필수) - 계좌 잔고 조회
   - ✅ **주문 조회** (필수) - 거래 내역 조회
   - ✅ **주문하기** (선택) - 실거래 시 필요
   - ⚠️ **출금하기** (권장하지 않음) - 보안상 사용하지 마세요

3. **IP 주소 제한 설정** (선택사항):
   - 개발 환경: `0.0.0.0/0` (모든 IP 허용, 테스트용)
   - 프로덕션: 특정 IP만 허용 (예: `123.45.67.89`)

4. **발급 완료 후 키 복사**:
   - **Access Key**: `abcdef1234567890abcdef1234567890`
   - **Secret Key**: `ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890` (⚠️ 단 한 번만 표시됨!)

⚠️ **중요**: Secret Key는 발급 시 단 한 번만 보여집니다. 반드시 안전한 곳에 보관하세요!

---

## 2. 환경변수 설정

### 2.1 .env 파일 생성

프로젝트 루트 디렉토리에 `.env` 파일을 생성합니다:

```bash
cd /home/user/engine
cp .env.example .env
```

### 2.2 .env 파일 편집

`.env` 파일을 열어 업비트 API 키를 입력합니다:

```bash
# MySQL 데이터베이스 설정
MYSQL_ROOT_PASSWORD=your_root_password
MYSQL_USER=eth_user
MYSQL_PASSWORD=eth_password

# 업비트 API 설정
UPBIT_ACCESS_KEY=your_upbit_access_key_here
UPBIT_SECRET_KEY=your_upbit_secret_key_here
```

**예시**:
```bash
UPBIT_ACCESS_KEY=abcdef1234567890abcdef1234567890
UPBIT_SECRET_KEY=ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890
```

### 2.3 Docker Compose로 실행

```bash
docker-compose up --build
```

---

## 3. API 연결 테스트

### 3.1 서버 실행 확인

서버가 정상적으로 실행되었는지 확인:

```bash
curl http://localhost:8080/actuator/health
```

**예상 응답**:
```json
{
  "status": "UP"
}
```

### 3.2 업비트 API 연결 테스트

업비트 API 키가 올바르게 설정되었는지 테스트:

```bash
curl http://localhost:8080/api/v1/account/test
```

**성공 시**:
```json
{
  "status": "ok",
  "message": "업비트 API 연결 성공"
}
```

**실패 시**:
```json
{
  "status": "error",
  "message": "업비트 API 연결 실패: ..."
}
```

---

## 4. 사용 가능한 API 엔드포인트

### 4.1 전체 잔고 조회

**요청**:
```bash
curl http://localhost:8080/api/v1/account/balance
```

**응답 예시**:
```json
[
  {
    "currency": "KRW",
    "balance": 1000000.0,
    "locked": 0.0,
    "avgBuyPrice": 0.0,
    "avgBuyPriceModified": false,
    "unitCurrency": "KRW"
  },
  {
    "currency": "ETH",
    "balance": 0.5,
    "locked": 0.0,
    "avgBuyPrice": 5000000.0,
    "avgBuyPriceModified": true,
    "unitCurrency": "KRW"
  }
]
```

### 4.2 KRW/ETH 잔고 요약

**요청**:
```bash
curl http://localhost:8080/api/v1/account/balance/summary
```

**응답 예시**:
```json
{
  "KRW": 1000000.0,
  "ETH": 0.5
}
```

### 4.3 특정 통화 잔고 조회

**요청**:
```bash
curl http://localhost:8080/api/v1/account/balance/KRW
```

**응답 예시**:
```json
{
  "currency": "KRW",
  "balance": 1000000.0
}
```

---

## 5. 보안 주의사항

### ⚠️ 필수 보안 규칙

1. **API 키 절대 공개 금지**:
   - GitHub, Slack 등 공개 채널에 공유 금지
   - `.env` 파일은 `.gitignore`에 포함되어 Git에 추적되지 않음

2. **Secret Key 안전한 보관**:
   - 비밀번호 관리 프로그램 사용 권장 (1Password, LastPass 등)
   - Secret Key는 발급 시 단 한 번만 표시됨

3. **IP 주소 제한 설정**:
   - 개발 환경: 모든 IP 허용 (`0.0.0.0/0`)
   - 프로덕션 환경: 특정 서버 IP만 허용

4. **권한 최소화**:
   - 테스트 시: "자산 조회", "주문 조회"만 허용
   - 실거래 시: "주문하기" 추가
   - "출금하기" 권한은 절대 허용하지 마세요

5. **정기적인 키 갱신**:
   - 3~6개월마다 API 키 재발급 권장
   - 유출 의심 시 즉시 키 삭제 후 재발급

### 🛡️ 키 유출 시 대응

1. **업비트 웹사이트 로그인**
2. **내 정보 > Open API 관리**
3. **해당 키 삭제** (즉시)
4. **새로운 키 재발급**
5. **서버 재시작**

---

## 6. 문제 해결 (Troubleshooting)

### 6.1 "Invalid API Key" 오류

**원인**: API 키가 잘못 입력됨

**해결방법**:
1. `.env` 파일의 API 키 확인
2. 공백, 줄바꿈 문자 제거
3. 업비트에서 키가 삭제되지 않았는지 확인

### 6.2 "IP 주소 제한" 오류

**원인**: 서버 IP가 허용 목록에 없음

**해결방법**:
1. 업비트 Open API 관리 페이지에서 IP 주소 확인
2. `0.0.0.0/0` (모든 IP 허용)으로 임시 변경
3. 개발 완료 후 특정 IP로 제한

### 6.3 "Too Many Requests" 오류

**원인**: API 호출 횟수 초과 (Rate Limit)

**해결방법**:
- 업비트 API Rate Limit:
  - **초당 8회**
  - **분당 200회**
- 호출 간격을 늘려서 재시도

---

## 7. 다음 단계

Phase 1 완료 후 다음 기능 구현:

- [ ] Phase 2: 잔고 조회 기능 (✅ 완료)
- [ ] Phase 3: 현재가 조회 기능
- [ ] Phase 4: 시장가 매수/매도 주문
- [ ] Phase 5: 주문 내역 및 체결 확인
- [ ] Phase 6: 거래 기록 DB 동기화
- [ ] Phase 7: 웹 UI - 수동 거래 테스트 페이지

---

## 📞 문의

문제가 발생하면 다음 로그를 확인하세요:

```bash
docker-compose logs -f app
```

업비트 API 공식 문서: https://docs.upbit.com
