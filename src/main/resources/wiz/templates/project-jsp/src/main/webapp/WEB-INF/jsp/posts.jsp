<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ include file="fragments/head.jspf" %>
<%@ include file="fragments/shell-start.jspf" %>
<section class="page-heading"><div><span class="eyebrow">CONTENT</span><h1>게시물</h1><p>검색, 페이지네이션, CRUD API를 한 화면에서 확인하세요.</p></div><a class="primary-button" href="${pageContext.request.contextPath}/posts/new">새 게시물</a></section>
<section class="panel filter-panel"><form id="post-filter" class="filter-form"><label class="search-field"><span>⌕</span><input name="text" type="search" placeholder="제목과 내용 검색"></label><label><span class="sr-only">카테고리</span><select name="category" id="category-filter"><option value="">모든 카테고리</option></select></label><button class="secondary-button" type="submit">검색</button></form></section>
<section class="panel post-panel"><div class="panel-heading"><div><h2>콘텐츠 목록</h2><p id="post-count"></p></div></div><div id="post-results"><div class="loading-panel"><span class="spinner"></span></div></div><div id="post-pagination"></div></section>
<script type="module" src="${pageContext.request.contextPath}/assets/js/pages/posts.js"></script>
<%@ include file="fragments/shell-end.jspf" %>
