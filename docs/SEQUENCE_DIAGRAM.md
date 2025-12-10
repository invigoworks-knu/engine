# System Sequence Diagrams

이 문서는 트레이딩 엔진의 주요 흐름을 시퀀스 다이어그램으로 표현합니다.

## 1. CUSUM Signal Backtesting Flow

AI가 생성한 매매 신호(CSV)를 기반으로 백테스팅을 수행하는 흐름입니다.

```mermaid
sequenceDiagram
    autonumber

    actor User
    participant Controller as BacktestController
    participant Service as CusumSignalBacktestService
    participant CSV as CSV Signal File
    participant MinuteRepo as MinuteOhlcvRepository
    participant Calculator as Kelly Position Sizer
    participant Tracker as TP/SL Tracker

    User->>Controller: POST /api/backtest/cusum<br/>(strategy, model, fold)
    activate Controller

    Controller->>Service: runCusumBacktest(request)
    activate Service

    Service->>CSV: Load backend_signals_master.csv
    CSV-->>Service: All trading signals

    Service->>Service: Filter BUY signals<br/>(by strategy/model/fold)

    loop For each BUY signal
        Service->>MinuteRepo: Find entry candle<br/>(signal timestamp)
        MinuteRepo-->>Service: Entry price candle

        Service->>Calculator: Calculate position size<br/>(suggested_weight, capital)
        Calculator-->>Service: Position size (Kelly)

        Service->>MinuteRepo: Stream minute candles<br/>(entry → expiration)
        MinuteRepo-->>Service: Stream of OHLCV data

        loop Minute-by-minute tracking
            Service->>Tracker: Check TP/SL/Timeout
            alt Take Profit hit
                Tracker-->>Service: TP exit (profit)
            else Stop Loss hit
                Tracker-->>Service: SL exit (loss)
            else Expiration reached
                Tracker-->>Service: Timeout exit
            end
        end

        Service->>Service: Update capital<br/>Record trade result
    end

    Service->>Service: Calculate statistics<br/>(Win rate, Sharpe, MDD)
    Service-->>Controller: BacktestResponse<br/>(trades, metrics)
    deactivate Service

    Controller-->>User: 200 OK + Results
    deactivate Controller
```

### 핵심 포인트
- **CSV 기반**: AI가 미리 생성한 신호 (entry time, TP/SL prices, confidence)
- **1분봉 정밀도**: Look-ahead bias 방지를 위한 분 단위 추적
- **Kelly Criterion**: 신뢰도 기반 포지션 사이징
- **승률 계산**: Timeout은 제외하고 TP/(TP+SL)로 계산

---

## 2. Real Trading Execution Flow

실제 Upbit 거래소에서 매매를 실행하는 흐름입니다.

```mermaid
sequenceDiagram
    autonumber

    actor User
    participant Controller as TradingController
    participant Service as TradingService
    participant SettingsService as TradingSettingsService
    participant AccountService as AccountService
    participant UpbitAPI as Upbit API Client
    participant DB as TradeOrderRepository
    participant Upbit as Upbit Exchange

    User->>Controller: POST /api/v1/trading/market-buy<br/>(market, amount)
    activate Controller

    Controller->>Service: executeBuy(market, amount)
    activate Service

    %% Safety Validation Phase
    rect rgb(255, 245, 230)
        Note over Service,SettingsService: Safety Validation
        Service->>SettingsService: Get trading settings
        SettingsService-->>Service: Settings (enabled, limits)

        Service->>Service: Validate safety rules<br/>- Market allowed?<br/>- Amount in range?<br/>- Daily limit OK?

        alt Safety check failed
            Service-->>Controller: Error: Safety violation
            Controller-->>User: 400 Bad Request
        end
    end

    %% Balance Check Phase
    Service->>AccountService: Get KRW balance
    activate AccountService
    AccountService->>UpbitAPI: fetchAccounts()
    UpbitAPI->>Upbit: GET /v1/accounts<br/>(with JWT auth)
    Upbit-->>UpbitAPI: Account list
    UpbitAPI-->>AccountService: Parsed balances
    AccountService-->>Service: KRW balance
    deactivate AccountService

    alt Insufficient balance
        Service-->>Controller: Error: Insufficient funds
        Controller-->>User: 400 Bad Request
    end

    %% Order Execution Phase
    rect rgb(230, 245, 255)
        Note over Service,Upbit: Order Execution
        Service->>UpbitAPI: placeOrder(MARKET_BUY)
        activate UpbitAPI
        UpbitAPI->>UpbitAPI: Generate JWT token<br/>(with query hash)
        UpbitAPI->>Upbit: POST /v1/orders<br/>(market, side, price)
        Upbit-->>UpbitAPI: Order UUID + status
        UpbitAPI-->>Service: Order response
        deactivate UpbitAPI
    end

    %% Persistence Phase
    Service->>DB: Save TradeOrder entity<br/>(uuid, market, amount, status)
    DB-->>Service: Saved order

    Service-->>Controller: OrderResponse<br/>(uuid, executed_volume)
    deactivate Service

    Controller-->>User: 200 OK + Order details
    deactivate Controller

    %% Async order sync (optional)
    Note over Service,Upbit: Later: Sync order status
    Service->>UpbitAPI: fetchOrder(uuid)
    UpbitAPI->>Upbit: GET /v1/order?uuid=...
    Upbit-->>UpbitAPI: Latest order state
    UpbitAPI-->>Service: Updated status
    Service->>DB: Update order status
```

### 핵심 포인트
- **다층 안전장치**: Settings validation → Balance check → Execution
- **인증**: JWT 토큰 기반 Upbit API 호출
- **추적성**: 모든 주문을 DB에 저장 및 동기화
- **비동기 동기화**: 주문 상태를 나중에 업데이트 가능

---

## 3. Historical Data Pipeline Flow

Upbit에서 과거 캔들 데이터를 수집하는 흐름입니다.

```mermaid
sequenceDiagram
    autonumber

    actor Admin
    participant Controller as DataPipelineController
    participant Service as DataPipelineService
    participant UpbitAPI as Upbit API Client
    participant Upbit as Upbit Public API
    participant DB as HistoricalOhlcvRepository

    Admin->>Controller: POST /api/v1/data/load-historical<br/>(market, startDate)
    activate Controller

    Controller->>Service: loadAllHistoricalOhlcv(market, startDate)
    activate Service

    Service->>Service: Initialize<br/>currentDate = today<br/>stopDate = startDate

    loop While currentDate > stopDate
        Service->>UpbitAPI: fetchDayCandles(market, 200, currentDate)
        activate UpbitAPI
        UpbitAPI->>Upbit: GET /v1/candles/days<br/>?market={market}&count=200&to={date}
        Upbit-->>UpbitAPI: 200 candles (JSON)
        UpbitAPI-->>Service: List<CandleDto>
        deactivate UpbitAPI

        Service->>Service: Convert DTO → Entity

        Service->>DB: saveAll(candles)
        DB-->>Service: Saved 200 candles

        Service->>Service: Update currentDate<br/>(oldest candle date)

        Service->>Service: Sleep 200ms<br/>(rate limit)

        Note over Service: Progress: {currentDate}
    end

    Service-->>Controller: Loaded {totalCount} candles
    deactivate Service

    Controller-->>Admin: 200 OK<br/>"Data loaded successfully"
    deactivate Controller
```

### 핵심 포인트
- **배치 수집**: 한 번에 200개 캔들씩 페이지네이션
- **역방향 수집**: 최신 → 과거 방향으로 수집
- **Rate Limiting**: API 호출 간 200ms 대기
- **동일 흐름**: 분봉(MinuteOhlcv)도 동일한 패턴

---

## 4. AI Model Backtesting Flow (TP/SL)

DB에 저장된 AI 예측을 기반으로 백테스팅하는 흐름입니다.

```mermaid
sequenceDiagram
    autonumber

    actor User
    participant Controller as BacktestController
    participant Service as TpSlBacktestService
    participant PredRepo as AiPredictionRepository
    participant MinuteRepo as MinuteOhlcvRepository
    participant PositionMgr as Position Manager

    User->>Controller: POST /api/backtest/tp-sl<br/>(model, fold, threshold)
    activate Controller

    Controller->>Service: runTpSlBacktest(request)
    activate Service

    Service->>PredRepo: Find predictions<br/>(model, fold, proba >= threshold)
    PredRepo-->>Service: List of AI predictions

    Service->>Service: Sort by prediction date

    loop For each prediction
        Service->>PositionMgr: Check existing position
        alt Position already open
            PositionMgr-->>Service: Skip (prevent overlap)
        else No position
            Service->>MinuteRepo: Get entry candle<br/>(next day 9:00 AM)
            MinuteRepo-->>Service: Entry price

            Service->>Service: Calculate position size<br/>(Kelly × confidence)

            Service->>MinuteRepo: Stream 8-day candles<br/>(1-minute resolution)
            MinuteRepo-->>Service: Minute candles stream

            loop Minute tracking (max 8 days)
                Service->>Service: Check TP/SL conditions

                alt TP reached
                    Service->>PositionMgr: Close position (profit)
                    Note over Service: Exit loop
                else SL reached
                    Service->>PositionMgr: Close position (loss)
                    Note over Service: Exit loop
                else 8 days passed
                    Service->>PositionMgr: Force close (timeout)
                    Note over Service: Exit loop
                end
            end

            Service->>Service: Apply fees (0.05% × 2)<br/>Update capital
        end
    end

    Service->>Service: Calculate metrics<br/>(Sharpe, MDD, win rate)
    Service-->>Controller: BacktestResponse
    deactivate Service

    Controller-->>User: 200 OK + Results
    deactivate Controller
```

### 핵심 포인트
- **DB 기반**: HistoricalAiPrediction 테이블에서 예측 로드
- **포지션 중복 방지**: 기존 포지션 열려있으면 새 진입 스킵
- **8일 홀딩**: 최대 보유 기간 후 강제 청산
- **수수료 적용**: 진입/청산 각 0.05%

---

## Architecture Overview

```mermaid
graph TB
    subgraph "Presentation Layer"
        BC[BacktestController]
        TC[TradingController]
        DC[DataPipelineController]
    end

    subgraph "Application Layer"
        CBS[CusumSignalBacktestService]
        TBS[TpSlBacktestService]
        TS[TradingService]
        DPS[DataPipelineService]
    end

    subgraph "Domain Layer"
        ER[Entity Repositories]
        E[Entities<br/>AiPrediction, MinuteOhlcv, TradeOrder]
    end

    subgraph "Infrastructure Layer"
        UA[UpbitApiClient]
        CSV[CSV Loader]
    end

    subgraph "External"
        UP[Upbit Exchange API]
        FS[File System]
    end

    BC --> CBS
    BC --> TBS
    TC --> TS
    DC --> DPS

    CBS --> ER
    TBS --> ER
    TS --> ER
    DPS --> ER

    CBS --> CSV
    TS --> UA
    DPS --> UA

    UA --> UP
    CSV --> FS
    ER --> E

    style BC fill:#e1f5ff
    style TC fill:#e1f5ff
    style DC fill:#e1f5ff
    style CBS fill:#fff4e1
    style TBS fill:#fff4e1
    style TS fill:#fff4e1
    style DPS fill:#fff4e1
    style UA fill:#ffe1f5
    style CSV fill:#ffe1f5
```

---

## 주요 설계 특징

### 1. **Hexagonal Architecture (Ports & Adapters)**
- **Domain**: 비즈니스 로직 (포지션 사이징, 위험 계산)
- **Inbound Adapters**: REST Controllers
- **Outbound Adapters**: Upbit API, JPA Repositories
- **Application Services**: 오케스트레이션 레이어

### 2. **백테스팅 정밀도**
- 1분봉 사용으로 Look-ahead bias 방지
- TP/SL 동시 도달 시 캔들 방향성으로 판단 (open vs close)
- 수수료 반영 (0.05% 양방향)

### 3. **안전 장치**
- 다층 검증 (TradingSettings → Balance → Execution)
- 일일 거래 횟수 제한
- 최소/최대 거래 금액 설정
- 허용 마켓 화이트리스트

### 4. **데이터 파이프라인**
- Pagination (200개씩)
- Rate limiting (200ms 대기)
- 역방향 수집 (최신 → 과거)
- 스트림 기반 메모리 효율성

### 5. **Kelly Criterion 포지션 사이징**
- Conservative Kelly
- Estimation Risk Kelly
- Half/Quarter Kelly
- Confidence-weighted variants

---

## 기술 스택

- **Framework**: Spring Boot 3.x
- **Database**: PostgreSQL (JPA/Hibernate)
- **HTTP Client**: WebClient (Reactive)
- **Authentication**: JWT (Upbit API)
- **Data Processing**: Stream API
- **Backtesting**: Custom engine (minute-level precision)
