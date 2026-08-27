<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ include file="fragments/head.jspf" %>
<main class="access-page">
    <section class="access-intro">
        <span class="eyebrow">SPRING MVC + JSP</span>
        <h1>서버 렌더링도<br>현대적인 흐름으로.</h1>
        <p>Spring MVC route와 JSP fragment, 표준 JSON API와 SSE를 함께 사용하는 실전형 시작점입니다.</p>
        <ul><li>✓ 세션 기반 페이지 보호</li><li>✓ JSP view와 재사용 fragment</li><li>✓ API CRUD와 실시간 채팅</li></ul>
    </section>
    <section class="login-card">
        <div class="login-heading"><span class="brand-mark large">W</span><div><h2>샘플에 로그인</h2><p>준비된 관리자 계정으로 시작하세요.</p></div></div>
        <form id="login-form" class="form-stack">
            <label>이메일<input name="email" type="email" value="admin@example.com" autocomplete="username" required></label>
            <label>비밀번호<input name="password" type="password" value="admin1234" autocomplete="current-password" required></label>
            <p class="form-error" id="login-error" hidden></p>
            <button class="primary-button full-button" type="submit">로그인</button>
        </form>
        <button class="demo-account" id="demo-account" type="button"><span class="avatar">A</span><span><strong>Demo administrator</strong><small>admin@example.com / admin1234</small></span></button>
    </section>
</main>
<div class="toast-region" id="toast-region" aria-live="polite"></div>
<script type="module" src="${pageContext.request.contextPath}/assets/js/pages/access.js"></script>
</body>
</html>
