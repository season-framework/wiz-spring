<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ include file="fragments/head.jspf" %>
<%@ include file="fragments/shell-start.jspf" %>
<section class="page-heading"><div><span class="eyebrow">OVERVIEW</span><h1>Dashboard</h1><p><spring:escapeBody htmlEscape="true">${sessionUser.name}</spring:escapeBody>님, 서비스의 최신 상태입니다.</p></div><a class="primary-button" href="${pageContext.request.contextPath}/posts/new">새 게시물</a></section>
<section class="stat-grid" id="dashboard-stats"><div class="loading-panel"><span class="spinner"></span></div></section>
<section class="dashboard-grid"><article class="panel"><div class="panel-heading"><div><h2>최근 게시물</h2><p>최근 콘텐츠와 공개 상태</p></div><a href="${pageContext.request.contextPath}/posts">전체 보기 →</a></div><div id="dashboard-recent"></div></article><aside class="panel quick-panel"><div class="panel-heading"><div><h2>빠른 시작</h2><p>샘플의 주요 기능</p></div></div><a href="${pageContext.request.contextPath}/members"><span>◎</span><strong>팀 멤버 관리</strong><b>→</b></a><a href="${pageContext.request.contextPath}/chat"><span>◇</span><strong>실시간 채팅</strong><b>→</b></a><a href="${pageContext.request.contextPath}/swagger-ui" target="_blank" rel="noreferrer"><span>{ }</span><strong>Swagger UI</strong><b>↗</b></a></aside></section>
<script type="module" src="${pageContext.request.contextPath}/assets/js/pages/dashboard.js"></script>
<%@ include file="fragments/shell-end.jspf" %>
