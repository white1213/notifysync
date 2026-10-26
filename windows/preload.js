'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('notifySync', {
  getState: () => ipcRenderer.invoke('ns:get-state'),
  getNotifications: () => ipcRenderer.invoke('ns:get-notifications'),
  setSetting: (key, value) => ipcRenderer.invoke('ns:set-setting', key, value),
  confirmPair: (peerId) => ipcRenderer.invoke('ns:confirm-pair', peerId),
  rejectPair: (peerId) => ipcRenderer.invoke('ns:reject-pair', peerId),
  initiatePair: (target) => ipcRenderer.invoke('ns:initiate-pair', target),
  unpair: (peerId) => ipcRenderer.invoke('ns:unpair', peerId),
  clearHistory: () => ipcRenderer.invoke('ns:clear-history'),
  copy: (text) => ipcRenderer.invoke('ns:copy', text),
  fixFirewall: () => ipcRenderer.invoke('ns:fix-firewall'),
  quit: () => ipcRenderer.invoke('ns:quit'),
  onState: (cb) => ipcRenderer.on('state', (_e, s) => cb(s)),
  onNotifications: (cb) => ipcRenderer.on('notifications', (_e, list) => cb(list)),
  onPairRequest: (cb) => ipcRenderer.on('pair-request', (_e, info) => cb(info)),
  onPairWaiting: (cb) => ipcRenderer.on('pair-waiting', (_e, info) => cb(info)),
  onPairResult: (cb) => ipcRenderer.on('pair-result', (_e, r) => cb(r)),
  onSentinelAlert: (cb) => ipcRenderer.on('sentinel-alert', (_e, info) => cb(info)),
});
