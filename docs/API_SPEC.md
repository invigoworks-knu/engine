# REST API 명세서

이 문서는 경북대학교 2025-2 산학협력 프로젝트 "AI 기반 암호화폐 자동 투자 엔진"의 모든 REST API 엔드포인트를 설명합니다.

## 📋 목차
1. [Trading APIs](#trading-apis) - 거래 주문 관련
2. [Account APIs](#account-apis) - 계좌 잔고 조회
3. [Settings APIs](#settings-apis) - 거래 설정 관리
4. [Data APIs](#data-apis) - 데이터 적재 및 조회
5. [Backtest APIs](#backtest-apis) - TP/SL 백테스팅

---

## Trading APIs

### 1. 시장가 매수

AI 신호 기반 ETH 매수 주문을 실행합니다.

**Endpoint**: `POST /api/v1/trading/orders/buy`

**Request Body**:
```json
{
  "market": "KRW-ETH",
  "amount": 10000.0,
  "aiSignal": {
    "predProbaUp": 0.75,
    "confidence": 0.8,
    "modelName": "GRU"
  }
}
```

**Response** (200 OK):
```json
{
  "status": "success",
  "orderUuid": "abc123...",
  "market": "KRW-ETH",
  "side": "BUY",
  "price": 5000000.0,
  "amount": 0.002,
  "message": "매수 주문이 체결되었습니다."
}
```

---

### 2. 시장가 매도

ETH를 KRW로 매도합니다.

**Endpoint**: `POST /api/v1/trading/orders/sell`

**Request Body**:
```json
{
  "market": "KRW-ETH",
  "volume": 0.002
}
```

**Response** (200 OK):
```json
{
  "status": "success",
  "orderUuid": "def456...",
  "market": "KRW-ETH",
  "side": "SELL",
  "price": 5100000.0,
  "volume": 0.002,
  "message": "매도 주문이 체결되었습니다."
}
```

---

### 3. 로컬 주문 내역 조회

DB에 저장된 모든 주문 내역을 조회합니다.

**Endpoint**: `GET /api/v1/trading/orders/local`

**Response** (200 OK):
```json
[
  {
    "id": 1,
    "market": "KRW-ETH",
    "side": "BUY",
    "price": 5000000.0,
    "amount": 0.002,
    "status": "FILLED",
    "upbitOrderUuid": "abc123...",
    "createdAt": "2025-12-05T09:00:00"
  },
  {
    "id": 2,
    "market": "KRW-ETH",
    "side": "SELL",
    "price": 5100000.0,
    "amount": 0.002,
    "status": "FILLED",
    "upbitOrderUuid": "def456...",
    "createdAt": "2025-12-13T09:00:00"
  }
]
```

---

### 4. 업비트 주문 동기화

업비트 서버에서 실제 주문 상태를 가져와 로컬 DB를 업데이트합니다.

**Endpoint**: `POST /api/v1/trading/orders/sync-all`

**Response** (200 OK):
```json
{
  "status": "success",
  "synced_count": 15,
  "message": "동기화 완료: 15건"
}
```

---

## Account APIs

### 1. 전체 잔고 조회

업비트 계좌의 모든 자산을 조회합니다.

**Endpoint**: `GET /api/v1/account/balance`

**Response** (200 OK):
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

---

### 2. KRW/ETH 잔고 요약

KRW와 ETH 잔고만 간단히 조회합니다.

**Endpoint**: `GET /api/v1/account/balance/summary`

**Response** (200 OK):
```json
{
  "KRW": 1000000.0,
  "ETH": 0.5
}
```

---

### 3. 특정 통화 잔고 조회

특정 통화(KRW 또는 ETH)의 잔고만 조회합니다.

**Endpoint**: `GET /api/v1/account/balance/{currency}`

**Path Parameter**:
- `currency`: `KRW` 또는 `ETH`

**Response** (200 OK):
```json
{
  "currency": "KRW",
  "balance": 1000000.0
}
```

---

## Settings APIs

### 1. 거래 설정 조회

현재 거래 설정을 조회합니다.

**Endpoint**: `GET /api/v1/settings`

**Response** (200 OK):
```json
{
  "isEnabled": true,
  "minOrderAmount": 5000,
  "maxOrderAmount": 100000,
  "maxDailyTrades": 5,
  "allowedMarket": "KRW-ETH",
  "updatedAt": "2025-12-05T10:00:00"
}
```

---

### 2. 거래 설정 업데이트

거래 설정을 변경합니다.

**Endpoint**: `PUT /api/v1/settings`

**Request Body**:
```json
{
  "minOrderAmount": 10000,
  "maxOrderAmount": 200000,
  "maxDailyTrades": 10,
  "allowedMarket": "KRW-ETH"
}
```

**Response** (200 OK):
```json
{
  "status": "success",
  "message": "설정이 업데이트되었습니다.",
  "settings": { /* 업데이트된 설정 */ }
}
```

---

### 3. 안전장치 토글

거래 활성화/비활성화를 전환합니다.

**Endpoint**: `POST /api/v1/settings/toggle`

**Request Body**:
```json
{
  "enabled": false
}
```

**Response** (200 OK):
```json
{
  "isEnabled": false,
  "message": "안전장치가 비활성화되었습니다. 모든 신규 거래가 차단됩니다."
}
```

---

### 4. 설정 초기화

모든 설정을 기본값으로 복원합니다.

**Endpoint**: `POST /api/v1/settings/reset`

**Response** (200 OK):
```json
{
  "status": "success",
  "message": "설정이 초기화되었습니다.",
  "settings": { /* 기본값 설정 */ }
}
```

---

## Data APIs

### 1. 일봉 시세 적재

Upbit API에서 ETH 일봉 데이터를 가져와 DB에 저장합니다.

**Endpoint**: `POST /api/v1/data/init-ohlcv-all`

**Response** (200 OK):
```json
{
  "status": "success",
  "count": 2000,
  "message": "일봉 데이터 적재 완료: 2000건"
}
```

---

### 2. AI 예측값 적재

12개 AI 모델의 예측 데이터를 CSV에서 로드하여 DB에 저장합니다.

**Endpoint**: `POST /api/v1/data/init-multi-model-predictions-all`

**Response** (200 OK):
```json
{
  "status": "success",
  "total_loaded": 12000,
  "models": {
    "GRU": 1000,
    "LSTM": 1000,
    "BiLSTM": 1000,
    ...
  },
  "message": "12개 모델 예측 데이터 적재 완료"
}
```

---

### 3. 일봉 데이터 상태 조회

DB에 저장된 일봉 데이터 개수를 조회합니다.

**Endpoint**: `GET /api/v1/data/ohlcv/status`

**Response** (200 OK):
```json
{
  "count": 2000,
  "status": "ok"
}
```

---

### 4. AI 예측 데이터 상태 조회

DB에 저장된 AI 예측 데이터 개수를 조회합니다.

**Endpoint**: `GET /api/v1/data/predictions/status`

**Response** (200 OK):
```json
{
  "count": 12000,
  "status": "ok"
}
```

---

### 5. 1분봉 데이터 상태 조회

DB에 저장된 1분봉 데이터 개수를 조회합니다.

**Endpoint**: `GET /api/v1/data/minute-candles/status`

**Response** (200 OK):
```json
{
  "count": 1000000,
  "status": "ok"
}
```

---

### 6. 1분봉 데이터 적재

Upbit API에서 1분봉 데이터를 가져와 DB에 저장합니다.

**Endpoint**: `POST /api/v1/data/init-minute-candles`

**Query Parameters**:
- `startDate` (optional): 시작일 (YYYY-MM-DD, 기본값: 2022-12-07)
- `endDate` (optional): 종료일 (YYYY-MM-DD, 기본값: 2025-10-21)

**Response** (200 OK):
```json
{
  "status": "success",
  "count": 1000000,
  "message": "1분봉 데이터 적재 완료: 1,000,000건"
}
```

---

## Backtest APIs

### 1. TP/SL 백테스팅 실행 (단일)

단일 모델 + Fold 조합에 대해 TP/SL 백테스팅을 실행합니다.

**Endpoint**: `POST /api/backtest/tp-sl/run`

**Request Body**:
```json
{
  "foldNumber": 1,
  "modelName": "GRU",
  "initialCapital": 10000.0,
  "predProbaThreshold": 0.6,
  "holdingPeriodDays": 8
}
```

**Response** (200 OK):
```json
{
  "modelName": "GRU",
  "foldNumber": 1,
  "regime": "BEAR",
  "startDate": "2022-12-07",
  "endDate": "2023-03-06",
  "initialCapital": 10000.0,
  "finalCapital": 10500.0,
  "totalReturnPct": 5.0,
  "totalTrades": 20,
  "takeProfitExits": 12,
  "stopLossExits": 5,
  "timeoutExits": 3,
  "winRate": 60.0,
  "avgHoldingDays": 4.5,
  "maxDrawdown": 15.0,
  "sharpeRatio": 1.2,
  "avgWin": 8.5,
  "avgLoss": 5.2,
  "winLossRatio": 1.63,
  "tradeHistory": [
    {
      "tradeNumber": 1,
      "entryDate": "2022-12-08",
      "entryDateTime": "2022-12-08T09:00:00",
      "entryPrice": 5000000.0,
      "exitDate": "2022-12-12",
      "exitDateTime": "2022-12-12T14:30:00",
      "exitPrice": 5100000.0,
      "takeProfitPrice": 5150000.0,
      "stopLossPrice": 4900000.0,
      "positionSize": 1000.0,
      "investmentRatio": 0.1,
      "profit": 20.0,
      "returnPct": 2.0,
      "exitReason": "PROFIT_LADDER",
      "holdingDays": 4.2,
      "predProbaUp": 0.75,
      "confidence": 0.8,
      "capitalAfter": 10020.0,
      "exitEvents": [
        {
          "exitDateTime": "2022-12-10T10:00:00",
          "exitPrice": 5025000.0,
          "exitRatio": 0.3,
          "exitAmount": 300.0,
          "profit": 7.5,
          "returnPct": 0.5,
          "exitReason": "PROFIT_LADDER",
          "triggerCondition": "Return >= 5%"
        },
        {
          "exitDateTime": "2022-12-12T14:30:00",
          "exitPrice": 5100000.0,
          "exitRatio": 0.7,
          "exitAmount": 700.0,
          "profit": 14.0,
          "returnPct": 2.0,
          "exitReason": "TIMEOUT",
          "triggerCondition": "Day 8"
        }
      ]
    }
  ]
}
```

---

### 2. TP/SL 배치 백테스팅 (동기)

여러 모델과 Fold 조합에 대해 순차적으로 백테스팅을 실행합니다.

**Endpoint**: `POST /api/backtest/tp-sl/run-batch`

**Request Body**:
```json
{
  "modelNames": ["GRU", "LSTM", "BiLSTM"],
  "foldNumbers": [1, 2, 3],
  "initialCapital": 10000.0,
  "predProbaThreshold": 0.6,
  "holdingPeriodDays": 8
}
```

**Response** (200 OK):
```json
[
  {
    /* GRU + Fold 1 결과 */
  },
  {
    /* GRU + Fold 2 결과 */
  },
  ...
]
```

---

### 3. TP/SL 배치 백테스팅 (비동기)

여러 모델과 Fold 조합에 대해 백그라운드로 백테스팅을 실행합니다.

**Endpoint**: `POST /api/backtest/tp-sl/run-batch-async`

**Request Body**:
```json
{
  "modelNames": ["GRU", "LSTM", "BiLSTM", "XGBoost", "LightGBM", "CatBoost"],
  "foldNumbers": [1, 2, 3, 4, 5, 6, 7],
  "initialCapital": 10000.0,
  "predProbaThreshold": 0.6,
  "holdingPeriodDays": 8
}
```

**Response** (200 OK):
```json
{
  "jobId": "abc123-def456-...",
  "message": "배치 백테스팅 작업이 시작되었습니다."
}
```

---

### 4. 백테스팅 작업 상태 조회

비동기 백테스팅 작업의 진행 상황을 조회합니다.

**Endpoint**: `GET /api/backtest/tp-sl/job/{jobId}`

**Path Parameter**:
- `jobId`: 작업 ID (위 비동기 API에서 반환된 값)

**Response** (200 OK):
```json
{
  "jobId": "abc123-def456-...",
  "status": "RUNNING",
  "totalTasks": 42,
  "completedTasks": 25,
  "failedTasks": 0,
  "progress": 59,
  "errorMessage": null
}
```

**Status Values**:
- `PENDING`: 대기 중
- `RUNNING`: 실행 중
- `COMPLETED`: 완료
- `FAILED`: 실패

---

### 5. 백테스팅 작업 결과 조회

비동기 백테스팅 작업의 결과를 조회합니다.

**Endpoint**: `GET /api/backtest/tp-sl/job/{jobId}/results`

**Response** (200 OK):
```
"작업이 완료되었습니다. 동기 배치 API(/api/backtest/tp-sl/run-batch)를 다시 호출하여 결과를 조회하세요."
```

**참고**: 현재는 결과를 DB에 저장하지 않으므로, 프론트엔드에서 배치 API를 재호출해야 합니다.

---

## 에러 응답

모든 API는 에러 발생 시 다음 형식의 응답을 반환합니다:

**Response** (400 Bad Request / 500 Internal Server Error):
```json
{
  "status": "error",
  "message": "에러 메시지"
}
```

**일반적인 에러 코드**:
- `400 Bad Request`: 잘못된 요청 파라미터
- `404 Not Found`: 리소스를 찾을 수 없음
- `500 Internal Server Error`: 서버 내부 오류

---

## 인증 및 보안

**현재 버전 (개발 환경)**:
- 모든 API는 인증 없이 접근 가능
- Upbit API 키는 서버 환경변수로 관리

**향후 프로덕션 배포 시**:
- JWT 또는 OAuth 2.0 인증 추가 필요
- HTTPS 전용 통신
- Rate Limiting (속도 제한)
- API Key 기반 접근 제어

---

## 버전 정보

**API Version**: 1.0.0
**Last Updated**: 2025-12-05
**Base URL**: `http://localhost:8080`

---

## 문의

API 관련 문의사항이나 버그 리포트는 GitHub Issues에 등록해주세요.

**개발자**: 최기영, 박신영
**프로젝트**: 경북대학교 2025-2 산학협력 프로젝트
