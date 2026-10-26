@import de.gbv.logguy.server.web.PageContext
@param PageContext page
@param String tab
@template.layout.page(page = page, title = "files".equals(tab) ? "Dateien" : "search".equals(tab) ? "Archivsuche"
  : "In den Index laden", content = @`
<main class="admin-page">
  <nav class="page-tabs">
    <a href="/archive" class="${"files".equals(tab) ? "active" : null}">Dateien</a>
    <a href="/archive/search" class="${"search".equals(tab) ? "active" : null}">Archivsuche</a>
    <a href="/archive/load" class="${"load".equals(tab) ? "active" : null}">In den Index laden</a>
  </nav>
  @if("files".equals(tab))
  <div id="files"></div>
  <script type="module" src="/static/js/files.js"></script>
  @elseif("search".equals(tab))
  <div id="archive"></div>
  <script type="module" src="/static/js/archive.js"></script>
  @else
  <section id="reindex"></section>
  <script type="module" src="/static/js/reindex.js"></script>
  @endif
</main>
`)
