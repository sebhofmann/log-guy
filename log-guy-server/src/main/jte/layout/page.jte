@import gg.jte.Content
@import de.gbv.logguy.server.web.PageContext
@param PageContext page
@param String title
@param Content content
<!doctype html>
<html lang="de">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  @if(page.csrf() != null)
  <meta name="csrf-header" content="${page.csrf().getHeaderName()}">
  <meta name="csrf-token" content="${page.csrf().getToken()}">
  @endif
  <title>${title} · log-guy</title>
  <link rel="stylesheet" href="/static/css/log-guy.css">
  <link rel="icon" href="/static/favicon.svg" type="image/svg+xml">
</head>
<body>
@if(page.user() != null)
<header class="top">
  <a class="brand" href="/">log-guy</a>
  <nav>
    <a href="/" class="${"search".equals(page.active()) ? "active" : null}">Suche</a>
    @if(page.user().isAdmin())
    <a href="/admin/status" class="${"status".equals(page.active()) ? "active" : null}">Status</a>
    <a href="/admin/log" class="${"log".equals(page.active()) ? "active" : null}">Server-Log</a>
    @endif
  </nav>
  <form method="post" action="/logout" class="logout">
    <span class="user">${page.user().name()}</span>
    <input type="hidden" name="${page.csrf().getParameterName()}" value="${page.csrf().getToken()}">
    <button type="submit">Abmelden</button>
  </form>
</header>
@endif
${content}
</body>
</html>
