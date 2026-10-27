export interface Album {
  id:         string;
  title:      string;
  artist:     string;
  year:       number;
  genre:      string;
  image_url:  string;
  image_path: string;
  added_at?:  string;
}

export interface LibraryStats {
  totalAlbums:  number;
  totalArtists: number;
  totalGenres:  number;
  newestYear:   number;
  oldestYear:   number;
}

export type SortField = 'title' | 'artist' | 'year' | 'genre' | 'added_at';
export type SortOrder = 'asc' | 'desc';

export interface FilterState {
  search:    string;
  genre:     string;
  artist:    string;          // ← new
  yearFrom:  number | '';
  yearTo:    number | '';
  sortField: SortField;
  sortOrder: SortOrder;
}

export interface CSVRow {
  id:          string;
  album_title: string;
  artist:      string;
  year:        string;
  genre:       string;
  image_url:   string;
}

export type Queueable = Album;
