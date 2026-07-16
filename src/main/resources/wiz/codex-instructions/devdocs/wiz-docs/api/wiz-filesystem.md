# Filesystem

Spring runtime은 path traversal과 symlink escape를 막기 위해 core 내부에서 `SafePath`를 사용한다. App code도 다음 원칙을 따른다.

- 사용자 입력을 그대로 `Path.resolve`에 넣지 않는다.
- workspace root 또는 허용된 subdirectory를 기준으로 normalize한다.
- 다운로드는 `wiz.response().download(path, filename)`을 사용한다.
- 업로드 파일명은 directory separator와 quote를 제거하거나 서버 생성 id를 사용한다.
- `bundle/` 산출물을 source처럼 수정하지 않는다.
