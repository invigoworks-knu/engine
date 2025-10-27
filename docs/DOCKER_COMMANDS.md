# 🐳 이더리움 투자 엔진: Docker 명령어 가이드

이 프로젝트는 모든 개발/테스트 환경을 Docker Compose로 통일합니다.
아래 명령어들을 프로젝트 루트 디렉토리(docker-compose.yml 파일이 있는 곳)에서 실행하세요.

---

## 1. 기본 실행 (가장 자주 사용)
컨테이너를 빌드하고 포그라운드(로그가 보임)로 실행합니다.
```bash
docker-compose up --build
```
--build: 코드가 변경되었을 때, app 이미지를 새로 빌드한 후 실행합니다.

## 2. 백그라운드 실행
컨테이너를 백그라운드(Detached mode)로 실행합니다. 터미널을 닫아도 유지됩니다.
```Bash
docker-compose up -d --build
```

## 3. 중지 및 삭제
실행 중인 컨테이너를 중지하고 삭제합니다.
```Bash
docker-compose down
```
참고: db-data 볼륨은 삭제되지 않으므로 DB 데이터는 유지됩니다.

## 4. 로그 확인
백그라운드로 실행 중인 컨테이너의 실시간 로그를 확인합니다. (중지: Ctrl + C)
```Bash
docker-compose logs -f
```

## 5. 강제 재빌드 (캐시 문제 시)
빌드 캐시를 무시하고 이미지를 처음부터 다시 만듭니다.
```Bash
docker-compose build --no-cache
```

## 6. 기타 유용한 명령어
```Bash
# 현재 실행 중인 서비스 상태 확인
docker-compose ps

# DB 데이터까지 모두 삭제 (초기화)
# 주의: DB 데이터가 모두 사라집니다!
docker-compose down -v
```