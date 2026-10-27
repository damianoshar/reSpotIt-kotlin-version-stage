import { CapacitorSQLite, SQLiteConnection } from '@capacitor-community/sqlite';
import { Capacitor } from '@capacitor/core';
import { Album, LibraryStats } from '../types';

const sqlite = new SQLiteConnection(CapacitorSQLite);
let db: Awaited<ReturnType<typeof sqlite.createConnection>>;

export function getDb() { return db; }

export async function initDB(): Promise<void> {
  if (Capacitor.getPlatform() === 'web') {
    await CapacitorSQLite.initWebStore();
  }
  db = await sqlite.createConnection('albums_db', false, 'no-encryption', 1, false);
  await db.open();
  await db.execute(`
    CREATE TABLE IF NOT EXISTS albums (
      id         TEXT PRIMARY KEY,
      title      TEXT NOT NULL,
      artist     TEXT NOT NULL,
      year       INTEGER DEFAULT 0,
      genre      TEXT DEFAULT '',
      image_url  TEXT DEFAULT '',
      image_path TEXT DEFAULT '',
      added_at   TEXT DEFAULT ''
    )
  `);
  await db.execute(`
    CREATE TABLE IF NOT EXISTS playlists (
      id          TEXT PRIMARY KEY,
      name        TEXT NOT NULL,
      image_url   TEXT DEFAULT '',
      track_count INTEGER DEFAULT 0,
      album_count INTEGER DEFAULT 0,
      synced_at   TEXT DEFAULT ''
    )
  `);
  await db.execute(`
    CREATE TABLE IF NOT EXISTS playlist_albums (
      playlist_id  TEXT NOT NULL,
      album_id     TEXT NOT NULL,
      title        TEXT NOT NULL,
      artist       TEXT NOT NULL,
      year         INTEGER DEFAULT 0,
      genre        TEXT DEFAULT '',
      image_url    TEXT DEFAULT '',
      track_count  INTEGER DEFAULT 1,
      track_titles TEXT DEFAULT '[]',
      PRIMARY KEY (playlist_id, album_id)
    )
  `);
}

export async function upsertAlbum(album: Album): Promise<void> {
  await db.run(
    `INSERT OR REPLACE INTO albums
       (id, title, artist, year, genre, image_url, image_path, added_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    [album.id, album.title, album.artist, album.year,
     album.genre, album.image_url, album.image_path, album.added_at]
  );
}

// ---------------------------------------------------------------------------
// Shared WHERE builder
// ---------------------------------------------------------------------------

function buildWhere(
  search: string, genre: string, yearFrom: string, yearTo: string, artist: string
): { where: string; params: unknown[] } {
  const conditions: string[] = [];
  const params:     unknown[] = [];

  if (search) {
    conditions.push('(LOWER(title) LIKE ? OR LOWER(artist) LIKE ?)');
    const q = '%' + search.toLowerCase() + '%';
    params.push(q, q);
  }
  if (genre)    { conditions.push('LOWER(genre)  = LOWER(?)'); params.push(genre);  }
  if (artist)   { conditions.push('LOWER(artist) = LOWER(?)'); params.push(artist); }
  if (yearFrom) { conditions.push('year >= ?'); params.push(parseInt(yearFrom, 10)); }
  if (yearTo)   { conditions.push('year <= ?'); params.push(parseInt(yearTo,   10)); }

  return {
    where:  conditions.length ? 'WHERE ' + conditions.join(' AND ') : '',
    params,
  };
}

// ---------------------------------------------------------------------------
// Paginated query
// ---------------------------------------------------------------------------

export const PAGE_SIZE = 48;

export async function getAllAlbums(
  search    = '',
  genre     = '',
  sortField = 'artist',
  sortOrder = 'asc',
  yearFrom  = '',
  yearTo    = '',
  artist    = '',
  limit     = PAGE_SIZE,
  offset    = 0,
): Promise<Album[]> {
  const { where, params } = buildWhere(search, genre, yearFrom, yearTo, artist);
  const allowed = new Set(['title', 'artist', 'year', 'genre', 'added_at']);
  const col     = allowed.has(sortField) ? sortField : 'artist';
  const dir     = sortOrder === 'desc' ? 'DESC' : 'ASC';

  const result = await db.query(
    `SELECT * FROM albums ${where} ORDER BY LOWER(${col}) ${dir} LIMIT ? OFFSET ?`,
    [...params, limit, offset]
  );
  return (result.values ?? []) as Album[];
}

// Total count for the same filter — used for hasMore / result display
export async function countAlbums(
  search   = '',
  genre    = '',
  yearFrom = '',
  yearTo   = '',
  artist   = '',
): Promise<number> {
  const { where, params } = buildWhere(search, genre, yearFrom, yearTo, artist);
  const result = await db.query(
    `SELECT COUNT(*) AS n FROM albums ${where}`, params
  );
  return ((result.values ?? [{}]) as { n: number }[])[0]?.n ?? 0;
}

// ---------------------------------------------------------------------------
// Filter options
// ---------------------------------------------------------------------------

export async function getDistinctGenres(): Promise<string[]> {
  const result = await db.query(
    "SELECT DISTINCT genre FROM albums WHERE genre != '' ORDER BY genre"
  );
  return ((result.values ?? []) as { genre: string }[]).map((r) => r.genre);
}

export async function getDistinctArtists(): Promise<string[]> {
  const result = await db.query(
    "SELECT DISTINCT artist FROM albums WHERE artist != '' ORDER BY LOWER(artist)"
  );
  return ((result.values ?? []) as { artist: string }[]).map((r) => r.artist);
}

export async function getDistinctYears(): Promise<number[]> {
  const result = await db.query(
    'SELECT DISTINCT year FROM albums WHERE year > 0 ORDER BY year DESC'
  );
  return ((result.values ?? []) as { year: number }[]).map((r) => r.year);
}

// ---------------------------------------------------------------------------
// Stats & utils
// ---------------------------------------------------------------------------

type StatsRow = {
  totalAlbums: number; totalArtists: number;
  totalGenres: number; newestYear:   number; oldestYear: number;
};

export async function getStats(): Promise<LibraryStats> {
  const result = await db.query(`
    SELECT COUNT(*)               AS totalAlbums,
           COUNT(DISTINCT artist) AS totalArtists,
           COUNT(DISTINCT genre)  AS totalGenres,
           MAX(year)              AS newestYear,
           MIN(CASE WHEN year > 0 THEN year END) AS oldestYear
    FROM albums
  `);
  const row = ((result.values ?? [{}]) as StatsRow[])[0] ?? ({} as StatsRow);
  return {
    totalAlbums:  row.totalAlbums  ?? 0,
    totalArtists: row.totalArtists ?? 0,
    totalGenres:  row.totalGenres  ?? 0,
    newestYear:   row.newestYear   ?? 0,
    oldestYear:   row.oldestYear   ?? 0,
  };
}

export async function getExistingAlbumIds(): Promise<Set<string>> {
  const result = await db.query('SELECT id FROM albums');
  return new Set<string>(((result.values ?? []) as { id: string }[]).map((r) => r.id));
}

export async function clearLibrary(): Promise<void> {
  await db.execute('DELETE FROM albums');
}

export async function saveToStore(): Promise<void> {
  if (Capacitor.getPlatform() === 'web') {
    await CapacitorSQLite.saveToStore({ database: 'albums_db' });
  }
}
