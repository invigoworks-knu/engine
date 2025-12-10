# 트레이딩 엔진 시퀀스 다이어그램

암호화폐 트레이딩 엔진의 핵심 흐름을 시퀀스 다이어그램으로 표현합니다.

---

## 1. CUSUM 신호 백테스팅

AI가 생성한 CSV 신호를 기반으로 과거 데이터에서 백테스팅을 수행합니다.

```mermaid
sequenceDiagram
    autonumber

    actor 사용자
    participant 컨트롤러
    participant 백테스팅서비스
    participant 신호데이터
    participant 가격데이터

    사용자->>컨트롤러: 백테스팅 요청<br/>(전략, 모델, 파라미터)
    activate 컨트롤러

    컨트롤러->>백테스팅서비스: 백테스팅 실행
    activate 백테스팅서비스

    백테스팅서비스->>신호데이터: CSV 신호 로드
    신호데이터-->>백테스팅서비스: 매수/매도 신호 목록

    loop 각 거래 신호마다
        백테스팅서비스->>가격데이터: 1분봉 데이터 조회
        가격데이터-->>백테스팅서비스: 시계열 가격 데이터

        백테스팅서비스->>백테스팅서비스: 포지션 진입/청산 시뮬레이션<br/>(TP/SL 추적, Kelly 포지션 사이징)
    end

    백테스팅서비스->>백테스팅서비스: 성과 지표 계산<br/>(수익률, 샤프지수, MDD, 승률)

    백테스팅서비스-->>컨트롤러: 백테스팅 결과
    deactivate 백테스팅서비스

    컨트롤러-->>사용자: 결과 반환
    deactivate 컨트롤러
```

### 핵심 특징
- **AI 신호 기반**: CSV 파일에 저장된 AI 예측 신호 활용
- **1분봉 정밀 추적**: 분 단위로 TP/SL 도달 여부 확인
- **Kelly Criterion**: 신뢰도 기반 포지션 크기 결정

---

## 2. 실시간 거래 실행

Upbit 거래소에서 실제 매매를 실행합니다.

```mermaid
sequenceDiagram
    autonumber

    actor 사용자
    participant 컨트롤러
    participant 거래서비스
    participant 안전검증
    participant 거래소API
    participant 데이터베이스

    사용자->>컨트롤러: 매수/매도 요청<br/>(마켓, 금액)
    activate 컨트롤러

    컨트롤러->>거래서비스: 거래 실행
    activate 거래서비스

    rect rgb(255, 245, 230)
        Note over 거래서비스,안전검증: 안전성 검증
        거래서비스->>안전검증: 거래 가능 여부 확인
        안전검증-->>거래서비스: 설정 검증 완료<br/>(마켓 허용, 금액 범위, 일일 한도)

        거래서비스->>거래소API: 잔고 조회
        거래소API-->>거래서비스: KRW 잔고
    end

    rect rgb(230, 245, 255)
        Note over 거래서비스,거래소API: 주문 실행
        거래서비스->>거래소API: 시장가 주문 전송
        거래소API-->>거래서비스: 주문 체결 결과
    end

    거래서비스->>데이터베이스: 거래 내역 저장
    데이터베이스-->>거래서비스: 저장 완료

    거래서비스-->>컨트롤러: 거래 완료
    deactivate 거래서비스

    컨트롤러-->>사용자: 거래 결과 반환
    deactivate 컨트롤러
```

### 핵심 특징
- **다층 안전장치**: 설정 검증 → 잔고 확인 → 주문 실행
- **거래 추적**: 모든 주문 내역을 DB에 저장
- **실시간 동기화**: 주문 상태를 주기적으로 업데이트

---

## 3. 과거 데이터 수집

Upbit에서 과거 캔들 데이터를 수집하여 저장합니다.

```mermaid
sequenceDiagram
    autonumber

    actor 관리자
    participant 컨트롤러
    participant 데이터서비스
    participant 거래소API
    participant 데이터베이스

    관리자->>컨트롤러: 데이터 수집 요청<br/>(마켓, 기간)
    activate 컨트롤러

    컨트롤러->>데이터서비스: 과거 데이터 로드
    activate 데이터서비스

    loop 목표 날짜까지 반복
        데이터서비스->>거래소API: 캔들 데이터 요청<br/>(배치 단위: 200개)
        거래소API-->>데이터서비스: 캔들 데이터

        데이터서비스->>데이터베이스: 배치 저장
        데이터베이스-->>데이터서비스: 저장 완료

        Note over 데이터서비스: Rate Limiting<br/>(200ms 대기)
    end

    데이터서비스-->>컨트롤러: 수집 완료
    deactivate 데이터서비스

    컨트롤러-->>관리자: 결과 반환
    deactivate 컨트롤러
```

### 핵심 특징
- **배치 처리**: 200개씩 페이지네이션으로 수집
- **Rate Limiting**: API 호출 간격 제어
- **역방향 수집**: 최신 데이터부터 과거로 수집

---

## 4. AI 모델 백테스팅

데이터베이스에 저장된 AI 예측을 기반으로 백테스팅을 수행합니다.

```mermaid
sequenceDiagram
    autonumber

    actor 사용자
    participant 컨트롤러
    participant 백테스팅서비스
    participant AI예측DB
    participant 가격데이터

    사용자->>컨트롤러: 백테스팅 요청<br/>(모델, 임계값)
    activate 컨트롤러

    컨트롤러->>백테스팅서비스: 백테스팅 실행
    activate 백테스팅서비스

    백테스팅서비스->>AI예측DB: AI 예측 조회<br/>(임계값 이상)
    AI예측DB-->>백테스팅서비스: 예측 목록

    loop 각 예측마다
        백테스팅서비스->>가격데이터: 1분봉 데이터 조회
        가격데이터-->>백테스팅서비스: 시계열 가격 데이터

        alt 포지션 중복 없음
            백테스팅서비스->>백테스팅서비스: 거래 시뮬레이션<br/>(진입 → TP/SL 추적 → 청산)
        else 기존 포지션 존재
            백테스팅서비스->>백테스팅서비스: 신호 스킵
        end
    end

    백테스팅서비스->>백테스팅서비스: 성과 지표 계산

    백테스팅서비스-->>컨트롤러: 백테스팅 결과
    deactivate 백테스팅서비스

    컨트롤러-->>사용자: 결과 반환
    deactivate 컨트롤러
```

### 핵심 특징
- **DB 기반 예측**: 미리 저장된 AI 예측 활용
- **포지션 관리**: 중복 포지션 방지
- **홀딩 기간 제한**: 최대 8일 보유 후 강제 청산

---

## 시스템 아키텍처

```mermaid
graph TB
    subgraph "프레젠테이션 계층"
        API[REST API Controllers]
    end

    subgraph "애플리케이션 계층"
        BS[백테스팅 서비스]
        TS[거래 서비스]
        DS[데이터 서비스]
    end

    subgraph "도메인 계층"
        DB[(데이터베이스)]
    end

    subgraph "인프라 계층"
        UPBIT[Upbit API 클라이언트]
        CSV[CSV 로더]
    end

    subgraph "외부 시스템"
        EXCHANGE[Upbit 거래소]
        FILES[파일 시스템]
    end

    API --> BS
    API --> TS
    API --> DS

    BS --> DB
    TS --> DB
    DS --> DB

    BS --> CSV
    TS --> UPBIT
    DS --> UPBIT

    UPBIT --> EXCHANGE
    CSV --> FILES

    style API fill:#e1f5ff
    style BS fill:#fff4e1
    style TS fill:#fff4e1
    style DS fill:#fff4e1
    style UPBIT fill:#ffe1f5
    style CSV fill:#ffe1f5
```

---

## 주요 설계 원칙

### 1️⃣ 헥사고날 아키텍처
- **도메인 중심**: 비즈니스 로직이 핵심
- **포트와 어댑터**: 외부 시스템과의 결합도 최소화
- **테스트 용이성**: 각 계층 독립적으로 테스트 가능

### 2️⃣ 백테스팅 정밀도
- **1분봉 활용**: 실제 거래와 유사한 시뮬레이션
- **Look-ahead Bias 방지**: 미래 정보 사용 금지
- **수수료 반영**: 실제 거래 비용 고려

### 3️⃣ 안전성 우선
- **다층 검증**: 설정 → 잔고 → 실행 순차 확인
- **거래 제한**: 일일 한도, 금액 범위 설정
- **추적 가능성**: 모든 거래 기록 저장

### 4️⃣ 확장성
- **비동기 처리**: 대용량 백테스팅 지원
- **배치 처리**: 효율적인 데이터 수집
- **스트림 처리**: 메모리 효율적인 데이터 처리

---

## 기술 스택

| 계층 | 기술 |
|------|------|
| **프레임워크** | Spring Boot 3.x |
| **데이터베이스** | PostgreSQL |
| **HTTP 클라이언트** | WebClient (Reactive) |
| **인증** | JWT (Upbit API) |
| **데이터 처리** | Java Stream API |
| **백테스팅** | Custom Engine |
