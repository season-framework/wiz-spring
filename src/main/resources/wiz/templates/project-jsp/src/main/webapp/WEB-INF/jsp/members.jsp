<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ include file="fragments/head.jspf" %>
<%@ include file="fragments/shell-start.jspf" %>
<section class="page-heading"><div><span class="eyebrow">TEAM</span><h1>멤버</h1><p>역할을 확인하고 새 팀원을 초대하세요.</p></div><button class="primary-button" id="invite-toggle" type="button">멤버 초대</button></section>
<section class="panel form-panel" id="invite-panel" hidden><div class="panel-heading"><div><h2>새 멤버 초대</h2><p>초기 비밀번호는 <code>welcome1</code>입니다.</p></div></div><form id="invite-form" class="form-grid"><label>이메일<input name="email" type="email" required></label><label>이름<input name="name" maxlength="100"></label><label>역할<select name="role"><option value="user">사용자</option><option value="editor">편집자</option><option value="viewer">조회자</option><option value="admin">관리자</option></select></label><div class="form-actions"><p class="form-error" id="invite-error" hidden></p><button class="secondary-button" id="invite-cancel" type="button">취소</button><button class="primary-button" type="submit">초대</button></div></form></section>
<section id="member-list"><div class="loading-panel"><span class="spinner"></span></div></section>
<dialog class="detail-dialog" id="member-dialog"><button class="dialog-close" type="button">×</button><div id="member-detail"></div></dialog>
<script type="module" src="${pageContext.request.contextPath}/assets/js/pages/members.js"></script>
<%@ include file="fragments/shell-end.jspf" %>
