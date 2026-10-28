@import de.gbv.logguy.server.web.PageContext
@param PageContext page
@param boolean error
@param boolean logout
@param long waitMinutes
@template.layout.page(page = page, title = "Anmeldung", content = @`
<main class="login">
  <h1><img src="/static/img/logo.png" alt="log-guy" width="196" height="240"></h1>
  @if(waitMinutes > 0)
  <p class="message error">
    Zu viele Fehlversuche von dieser Adresse. Bitte in ${waitMinutes} ${waitMinutes == 1 ? "Minute" : "Minuten"}
    erneut versuchen.
  </p>
  @elseif(error)
  <p class="message error">Name oder Passwort ist falsch.</p>
  @elseif(logout)
  <p class="message">Du bist abgemeldet.</p>
  @endif
  <form method="post" action="/login">
    <label>Name <input name="username" autocomplete="username" required autofocus></label>
    <label>Passwort <input name="password" type="password" autocomplete="current-password" required></label>
    <input type="hidden" name="${page.csrf().getParameterName()}" value="${page.csrf().getToken()}">
    <button type="submit">Anmelden</button>
  </form>
</main>
`)
