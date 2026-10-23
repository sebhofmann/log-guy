@import de.gbv.logguy.server.web.PageContext
@param PageContext page
@param boolean error
@param boolean logout
@template.layout.page(page = page, title = "Anmeldung", content = @`
<main class="login">
  <h1>log-guy</h1>
  @if(error)
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
