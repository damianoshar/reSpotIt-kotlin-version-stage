import React, { useState, useRef } from 'react';
import { SyncResult } from '../services/SyncService';
import { BackupSettings } from '../hooks/useBackupSettings';
import { Capacitor } from '@capacitor/core';

type Tab    = 'spotify' | 'csv' | 'backup';
type Prompt = 'none' | 'confirm-clean';

interface Props {
  onSyncCSV:       (csv: string)    => Promise<SyncResult>;
  onSyncSpotify:   (clean: boolean) => Promise<SyncResult>;
  onExportNow:     ()               => Promise<void>;
  onClose:         ()               => void;
  syncing:         boolean;
  backup:          BackupSettings;
  onToggleBackup:  ()               => void;
  onSetSubdir:     (s: string)      => void;
}

function formatResult(r: SyncResult): string {
  const parts: string[] = [];
  if (r.imported > 0) parts.push(r.imported + ' added');
  if (r.existing > 0) parts.push(r.existing + ' already up to date');
  if (r.skipped  > 0) parts.push(r.skipped  + ' failed');
  return parts.join(' · ') || 'Nothing changed';
}

function formatDate(iso: string): string {
  if (!iso) return 'Never';
  try { return new Date(iso).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' }); }
  catch { return iso; }
}

export const SyncModal: React.FC<Props> = ({
  onSyncCSV, onSyncSpotify, onExportNow, onClose,
  syncing, backup, onToggleBackup, onSetSubdir,
}) => {
  const [tab,       setTab]       = useState<Tab>('spotify');
  const [prompt,    setPrompt]    = useState<Prompt>('none');
  const [result,    setResult]    = useState<SyncResult | null>(null);
  const [localErr,  setLocalErr]  = useState('');
  const [subdirVal, setSubdirVal] = useState(backup.subdirName);
  const [exporting, setExporting] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  const reset = () => { setResult(null); setLocalErr(''); setPrompt('none'); };

  const runSpotifySync = async (clean: boolean) => {
    setPrompt('none'); setResult(null); setLocalErr('');
    try { setResult(await onSyncSpotify(clean)); }
    catch (e: any) { setLocalErr(e?.message ?? 'Spotify sync failed'); }
  };

  const handlePickCSV = () => {
    if (Capacitor.getPlatform() !== 'web') {
      setLocalErr('Native file picking — use a CSV imported from your sync script.');
      return;
    }
    fileRef.current?.click();
  };

  const handleWebFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const text = await file.text();
    setResult(null); setLocalErr('');
    try { setResult(await onSyncCSV(text)); }
    catch (e: any) { setLocalErr(e?.message ?? 'CSV sync failed'); }
    e.target.value = '';
  };

  const handleExportNow = async () => {
    setExporting(true); setLocalErr('');
    try { await onExportNow(); }
    catch (e: any) { setLocalErr(e?.message ?? 'Export failed'); }
    finally { setExporting(false); }
  };

  const handleSubdirBlur = () => {
    if (subdirVal !== backup.subdirName) onSetSubdir(subdirVal);
  };

  // Backdrop click closes modal — sync keeps running in background
  const handleBackdropClick = (e: React.MouseEvent<HTMLDivElement>) => {
    if (e.target === e.currentTarget) onClose();
  };

  return (
    <div
      className="modal-overlay"
      role="dialog"
      aria-modal="true"
      aria-label="Sync library"
      onClick={handleBackdropClick}
    >
      <div className="modal sync-modal">

        {/* Header — close button always visible */}
        <div className="modal-header">
          <div className="modal-header-titles">
            <h2>Sync library</h2>
            {syncing && (
              <span className="modal-sync-hint">Sync running &mdash; safe to close</span>
            )}
          </div>
          <button
            className="modal-close"
            onClick={onClose}
            aria-label="Close — sync continues in background"
            title="Close — sync continues in background"
          >
            &#x2715;
          </button>
        </div>

        {/* Tabs */}
        <div className="modal-tabs" role="tablist">
          {(['spotify', 'csv', 'backup'] as Tab[]).map((t) => (
            <button key={t} role="tab" aria-selected={tab === t}
                    className={'modal-tab ' + (tab === t ? 'active' : '')}
                    onClick={() => { setTab(t); reset(); }}>
              {t === 'spotify' ? (
                <>
                  <svg viewBox="0 0 20 20" fill="none" width="14" height="14" aria-hidden="true">
                    <circle cx="10" cy="10" r="9" fill="#1DB954"/>
                    <path d="M6 13c3-2 7-2 10 0" stroke="#fff" strokeWidth="1.4" strokeLinecap="round"/>
                    <path d="M5 10c4-3 9-3 12 0" stroke="#fff" strokeWidth="1.4" strokeLinecap="round"/>
                    <path d="M7 7c2-1 6-1 8 0"   stroke="#fff" strokeWidth="1.4" strokeLinecap="round"/>
                  </svg>
                  {' Spotify'}
                </>
              ) : t === 'csv' ? 'CSV'
                : <>&#128190; Backup{backup.enabled && <span className="backup-dot" aria-label="auto-backup on" />}</>
              }
            </button>
          ))}
        </div>

        <div className="modal-body">

          {/* ── Spotify tab ─────────────────────────────────────────── */}
          {tab === 'spotify' && (
            <>
              <p className="sync-desc">
                Fetch your saved albums from Spotify.
                {' '}<strong>Incremental sync</strong> skips albums already in your library.
              </p>

              <button className={'sync-btn full-width ' + (syncing ? 'syncing' : '')}
                      onClick={() => runSpotifySync(false)} disabled={syncing}>
                {syncing
                  ? <><span className="spinner" aria-hidden="true" />&nbsp;Syncing&hellip;</>
                  : <><svg viewBox="0 0 20 20" fill="none" width="14" height="14" style={{verticalAlign:'middle',marginRight:'0.3em'}} aria-hidden="true"><path d="M17 10A7 7 0 1 1 3.5 6.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"/><path d="M17 4v6h-6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/></svg>Sync new albums</>}
              </button>

              {prompt !== 'confirm-clean' ? (
                <button className="sync-btn sync-btn--ghost full-width"
                        onClick={() => setPrompt('confirm-clean')} disabled={syncing}>
                  &#x21BA; Clean sync (reset)
                </button>
              ) : (
                <div className="clean-confirm">
                  <p className="clean-warn">
                    &#x26A0; This will <strong>delete all local data</strong> and re-download
                    your entire library from scratch.
                  </p>
                  <div className="clean-confirm-btns">
                    <button className="sync-btn" onClick={() => setPrompt('none')}>Cancel</button>
                    <button className="sync-btn sync-btn--danger" onClick={() => runSpotifySync(true)}>
                      Yes, clean sync
                    </button>
                  </div>
                </div>
              )}
            </>
          )}

          {/* ── CSV tab ─────────────────────────────────────────────── */}
          {tab === 'csv' && (
            <>
              <p className="sync-desc">Import a <strong>.csv</strong> file exported from your sync script.</p>
              <button className={'sync-btn full-width ' + (syncing ? 'syncing' : '')}
                      onClick={handlePickCSV} disabled={syncing}>
                {syncing ? <><span className="spinner" aria-hidden="true" /> Importing&hellip;</> : '\uD83D\uDCC2 Choose CSV file'}
              </button>
              <input ref={fileRef} type="file" accept=".csv" className="sr-only" onChange={handleWebFileChange} />
            </>
          )}

          {/* ── Backup tab ──────────────────────────────────────────── */}
          {tab === 'backup' && (
            <div className="backup-panel">
              <div className="backup-row">
                <div className="backup-row-label">
                  <span className="backup-row-title">Auto-backup after sync</span>
                  <span className="backup-row-sub">
                    Saves a CSV to your Documents folder after every successful sync
                  </span>
                </div>
                <button className={'toggle-btn ' + (backup.enabled ? 'on' : '')}
                        onClick={onToggleBackup} role="switch" aria-checked={backup.enabled}
                        aria-label="Toggle auto-backup">
                  <span className="toggle-thumb" />
                </button>
              </div>

              <div className="filter-group" style={{ marginTop: '0.75rem' }}>
                <label className="filter-label" htmlFor="backup-subdir">
                  Subdirectory (inside Documents/)
                </label>
                <div className="backup-path-row">
                  <span className="backup-path-prefix">Documents/</span>
                  <input id="backup-subdir" className="search-input backup-subdir-input"
                         type="text" placeholder="SpotifyBackups"
                         value={subdirVal}
                         onChange={(e) => setSubdirVal(e.target.value)}
                         onBlur={handleSubdirBlur}
                         onKeyDown={(e) => e.key === 'Enter' && handleSubdirBlur()} />
                </div>
                <p className="backup-path-hint">
                  Files saved as <code>spotify-library-YYYY-MM-DD.csv</code>
                </p>
              </div>

              <div className="backup-last">
                <span className="backup-last-label">Last backup</span>
                <span className="backup-last-value">{formatDate(backup.lastBackup)}</span>
                {backup.lastPath && backup.lastPath !== '(browser download)' && (
                  <span className="backup-last-path">{backup.lastPath}</span>
                )}
              </div>

              <button className={'sync-btn full-width ' + (exporting ? 'syncing' : '')}
                      onClick={handleExportNow} disabled={exporting || syncing}>
                {exporting
                  ? <><span className="spinner" aria-hidden="true" />&nbsp;Exporting&hellip;</>
                  : '\uD83D\uDCE5 Export now'}
              </button>

              {localErr && <div className="sync-error" role="alert">&#x26A0; {localErr}</div>}
            </div>
          )}

          {/* Shared result / error */}
          {tab !== 'backup' && result && !syncing && (
            <div className="sync-result" role="status">
              <span className="sync-result-ok">&#x2713;</span>
              {formatResult(result)}
              {result.errors.length > 0 && (
                <details className="sync-errors">
                  <summary>{result.errors.length} error{result.errors.length !== 1 ? 's' : ''}</summary>
                  <ul>{result.errors.slice(0, 10).map((e, i) => <li key={i}>{e}</li>)}</ul>
                </details>
              )}
            </div>
          )}
          {tab !== 'backup' && localErr && (
            <div className="sync-error" role="alert">&#x26A0; {localErr}</div>
          )}
        </div>
      </div>
    </div>
  );
};
