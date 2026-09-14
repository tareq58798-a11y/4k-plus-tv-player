const express = require('express');
const db = require('./db');
const { renderDevices } = require('./adminPage');

const app = express();
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

const MAC_PATTERN = /^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$/;

function normalizeMac(value) {
  return String(value || '').trim().toUpperCase();
}

app.post('/api/activate', async (req, res) => {
  const mac = normalizeMac(req.body.mac);
  const deviceKey = String(req.body.deviceKey || '').trim();
  if (!MAC_PATTERN.test(mac) || !deviceKey) {
    return res.status(400).json({ status: 'error', message: 'Invalid mac or deviceKey.' });
  }

  const device = await db.upsertPendingDevice(mac, deviceKey);

  if (!device || device.status !== 'assigned' || device.device_key !== deviceKey) {
    return res.json({ status: 'pending' });
  }

  if (device.playlist_type === 'm3u' && device.m3u_url) {
    return res.json({ status: 'assigned', type: 'm3u', name: device.playlist_name, url: device.m3u_url });
  }
  if (device.playlist_type === 'xtream' && device.xtream_server) {
    return res.json({
      status: 'assigned',
      type: 'xtream',
      name: device.playlist_name,
      server: device.xtream_server,
      username: device.xtream_username,
      password: device.xtream_password
    });
  }
  return res.json({ status: 'pending' });
});

// In-memory only (cleared on restart) - cheap insurance against repeat lookups burning through
// the YouTube Data API's daily quota (search.list costs 100 of the free tier's 10,000 units/day,
// so only ~100 unique searches/day are free - this cache is what keeps popular titles from
// re-spending quota every time a different device asks for the same trailer).
const trailerCache = new Map();

app.get('/api/trailer', async (req, res) => {
  const title = String(req.query.title || '').trim();
  const year = String(req.query.year || '').trim();
  if (!title) return res.status(400).json({ videoId: null, message: 'Missing title.' });
  if (!process.env.YOUTUBE_API_KEY) return res.json({ videoId: null });

  const cacheKey = `${title.toLowerCase()}|${year}`;
  if (trailerCache.has(cacheKey)) return res.json({ videoId: trailerCache.get(cacheKey) });

  const query = [title, year, 'official trailer'].filter(Boolean).join(' ');
  const url = new URL('https://www.googleapis.com/youtube/v3/search');
  url.searchParams.set('part', 'snippet');
  url.searchParams.set('type', 'video');
  url.searchParams.set('maxResults', '1');
  url.searchParams.set('q', query);
  url.searchParams.set('key', process.env.YOUTUBE_API_KEY);

  let videoId = null;
  try {
    const response = await fetch(url);
    if (response.ok) {
      const data = await response.json();
      videoId = data.items?.[0]?.id?.videoId || null;
    }
  } catch {
    // Falls through to the null response below - the app falls back to a plain search page.
  }
  trailerCache.set(cacheKey, videoId);
  res.json({ videoId });
});

function requireAdmin(req, res, next) {
  const header = req.headers.authorization || '';
  const [scheme, encoded] = header.split(' ');
  if (scheme === 'Basic' && encoded) {
    const [user, pass] = Buffer.from(encoded, 'base64').toString().split(':');
    if (user === 'admin' && pass === process.env.ADMIN_PASSWORD) return next();
  }
  res.set('WWW-Authenticate', 'Basic realm="4K Plus TV Admin"');
  return res.status(401).send('Authentication required.');
}

app.get('/admin', requireAdmin, async (req, res) => {
  const devices = await db.listDevices();
  res.send(renderDevices(devices));
});

app.post('/admin/devices/:mac/assign', requireAdmin, async (req, res) => {
  const mac = normalizeMac(req.params.mac);
  const { playlistName, type, m3uUrl, xtreamServer, xtreamUsername, xtreamPassword } = req.body;
  if (type === 'm3u') {
    await db.assignM3u(mac, playlistName, m3uUrl);
  } else {
    await db.assignXtream(mac, playlistName, xtreamServer, xtreamUsername, xtreamPassword);
  }
  res.redirect('/admin');
});

app.post('/admin/devices/:mac/unassign', requireAdmin, async (req, res) => {
  await db.unassignDevice(normalizeMac(req.params.mac));
  res.redirect('/admin');
});

app.get('/', (req, res) => res.send('4K Plus TV activation server is running.'));

const port = process.env.PORT || 3000;
app.listen(port, () => console.log(`Activation server listening on port ${port}`));
