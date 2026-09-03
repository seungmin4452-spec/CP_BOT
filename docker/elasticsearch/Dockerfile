FROM docker.elastic.co/elasticsearch/elasticsearch:9.5.0

# 한국어 형태소 분석(BM25 키워드 검색 품질)을 위한 공식 nori 플러그인 설치.
# 베이스 이미지에는 기본 포함되어 있지 않아 커스텀 이미지로 빌드해야 한다.
RUN bin/elasticsearch-plugin install --batch analysis-nori
