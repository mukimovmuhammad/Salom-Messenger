'use strict';

const match = /^\/(u\/)?([A-Za-z][A-Za-z0-9_]{3,23})$/.exec(location.pathname);
const username = match?.[2].toLowerCase();
const openChat = document.getElementById('open-chat');
const hint = document.getElementById('profile-hint');

if (username) {
  document.getElementById('username').textContent = '@' + username;
  const onAndroid = /Android/i.test(navigator.userAgent);
  if (onAndroid) {
    // Chromium launches the installed app or opens the download page. Other
    // Android browsers can use the HTTPS link verified by the system.
    const chromium = /Chrome|Chromium|SamsungBrowser|Edge|EdgA/i.test(navigator.userAgent);
    if (chromium) {
      const fallback = encodeURIComponent('https://mukimov.site/#install');
      openChat.href = 'intent://profile/' + username
        + '#Intent;scheme=salom;package=com.mukimov.salom;S.browser_fallback_url=' + fallback + ';end';
    } else {
      openChat.href = match[1] ? 'salom://profile/' + username : '/u/' + username;
    }
    openChat.hidden = false;
  } else {
    hint.textContent = 'Найди @' + username + ' в приложении Салом или открой ссылку на Android.';
  }
} else {
  hint.textContent = 'Ссылка на профиль неполная.';
}
