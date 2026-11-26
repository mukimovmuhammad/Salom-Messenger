const motion = matchMedia('(prefers-reduced-motion: no-preference)');
const progress = document.querySelector('.progress');
let pending = false;
function drawProgress() {
  pending = false;
  const travel = document.documentElement.scrollHeight - innerHeight;
  progress.style.transform = 'scaleX(' + (travel > 0 ? Math.min(1, Math.max(0, scrollY / travel)) : 0) + ')';
}
function scheduleProgress() {
  if (pending) return;
  pending = true;
  requestAnimationFrame(drawProgress);
}
addEventListener('scroll', scheduleProgress, { passive: true });
addEventListener('resize', scheduleProgress, { passive: true });
document.querySelector('.questions').addEventListener('toggle', scheduleProgress, true);
scheduleProgress();

if (motion.matches && 'IntersectionObserver' in window) {
  document.documentElement.classList.add('motion');
  const observer = new IntersectionObserver(entries => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      entry.target.classList.add('visible');
      observer.unobserve(entry.target);
    }
  }, { threshold: .12 });
  document.querySelectorAll('.reveal').forEach(element => observer.observe(element));
}

const previews = document.querySelectorAll('[data-preview]');
let active = document.getElementById('demo-chat');
document.querySelector('.demo-switch').hidden = false;
previews.forEach(button => button.addEventListener('click', () => {
  const next = document.getElementById(button.dataset.preview);
  if (next === active) return;
  active.hidden = true;
  active.classList.remove('active');
  active = next;
  active.hidden = false;
  active.classList.add('active');
  previews.forEach(choice => choice.setAttribute('aria-pressed', String(choice === button)));
}));

const copy = document.querySelector('[data-copy]');
if (navigator.clipboard?.writeText) {
  copy.hidden = false;
  copy.addEventListener('click', async () => {
    const result = document.querySelector('.copy-result');
    try {
      await navigator.clipboard.writeText(copy.dataset.copy);
      result.textContent = 'Имя пользователя скопировано.';
    } catch {
      result.textContent = 'Найди в Саломе ' + copy.dataset.copy + '.';
    }
  });
}

const abiLabels = { 'arm64-v8a': 'ARM64', 'armeabi-v7a': 'ARM32', universal: 'Универсальный APK' };
const sizeLabel = bytes => new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  .format(bytes / 1_000_000) + ' МБ';
const validSize = bytes => Number.isSafeInteger(bytes) && bytes > 0;
async function detectAndroidAbi() {
  const hints = navigator.userAgentData;
  if (hints?.platform !== 'Android' || typeof hints.getHighEntropyValues !== 'function') return;
  try {
    const { architecture, bitness } = await hints.getHighEntropyValues(['architecture', 'bitness']);
    if (architecture !== 'arm') return;
    if (bitness === '64') return 'arm64-v8a';
    if (bitness === '32') return 'armeabi-v7a';
  } catch { /* Use the universal download when architecture is unavailable. */ }
}
function setDownload(anchor, name) {
  anchor.href = '/salom/' + name;
  anchor.download = name;
}
const deviceAbi = detectAndroidAbi();
async function loadRelease() {
  const response = await fetch('/salom/version.json', { cache: 'no-store' });
  if (!response.ok) return;
  const info = await response.json();
  if (typeof info.version !== 'string' || !/^\d+\.\d+\.\d+$/.test(info.version) || !validSize(info.size)) return;
  const releaseDate = document.querySelector('.release time');
  if (releaseDate && typeof info.releaseDate === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(info.releaseDate)) {
    const date = new Date(info.releaseDate + 'T00:00:00Z');
    if (!Number.isNaN(date.valueOf())) {
      releaseDate.dateTime = info.releaseDate;
      releaseDate.textContent = new Intl.DateTimeFormat('ru', {
        day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC'
      }).format(date);
    }
  }
  const label = info.version + (typeof info.displayName === 'string' && info.displayName ? ' ' + info.displayName : '');
  document.querySelectorAll('[data-version]').forEach(node => { node.textContent = label; });

  if (Array.isArray(info.releaseNotes)) {
    const notes = info.releaseNotes.filter(note => typeof note === 'string' && note.trim()).slice(0, 5);
    if (notes.length) document.querySelector('[data-release-notes]').replaceChildren(...notes.map(note => {
      const li = document.createElement('li');
      li.textContent = note;
      return li;
    }));
  }

  if (typeof info.windowsFile === 'string' && /^SALOM-\d+\.\d+\.\d+-(?:AllowCookie|CallFix|Release)-Setup\.exe$/.test(info.windowsFile)) {
    document.querySelectorAll('[data-windows]').forEach(node => setDownload(node, info.windowsFile));
    if (validSize(info.windowsSize)) document.querySelectorAll('[data-windows-summary]').forEach(node => {
      node.textContent = 'Setup.exe · ' + sizeLabel(info.windowsSize);
    });
  }

  const variants = new Map([['universal', info.size]]);
  for (const variant of Array.isArray(info.androidVariants) ? info.androidVariants : []) {
    if ((variant?.abi === 'arm64-v8a' || variant?.abi === 'armeabi-v7a') && validSize(variant.size)) {
      variants.set(variant.abi, variant.size);
    }
  }
  const apk = abi => 'salom-' + info.version + (abi === 'universal' ? '' : '-' + abi) + '.apk';
  document.querySelectorAll('[data-abi]').forEach(node => {
    const abi = node.dataset.abi;
    node.hidden = !variants.has(abi);
    if (node.hidden) return;
    setDownload(node, apk(abi));
    node.querySelector('small').textContent = sizeLabel(variants.get(abi));
  });
  function selectPrimary(abi) {
    setDownload(document.querySelector('[data-android-main]'), apk(abi));
    document.querySelector('[data-android-summary]').textContent = abiLabels[abi] + ' · ' + sizeLabel(variants.get(abi));
  }
  selectPrimary('universal');
  const abi = await deviceAbi;
  if (!variants.has(abi)) return;
  selectPrimary(abi);
  document.querySelector('[data-abi="' + abi + '"]').setAttribute('data-recommended', '');
  document.querySelector('[data-device-hint]').textContent = 'Для твоего устройства подойдёт ' + abiLabels[abi] + '.';
}
loadRelease().catch(() => { /* The page includes direct links for this release. */ });
