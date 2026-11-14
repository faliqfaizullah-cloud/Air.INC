'use strict';
const { contextBridge, ipcRenderer } = require('electron');
const OK = new Set(['lib:load', 'lib:scan', 'lib:add', 'lib:folders', 'lib:remove', 'lyrics:get', 'lyrics:retry', 'mem:save', 'mem:thumb', 'mem:pick', 'mem:del', 'mem:show',
  'upd:check', 'upd:get', 'upd:cancel', 'upd:install', 'theme', 'sys:bt', 'alarm:show', 'alarm:armed', 'win:max', 'win:state']);
const ON = new Set(['lib:update', 'upd:prog', 'win:state', 'media:key']);
contextBridge.exposeInMainWorld('AirHost', {
  platform: 'win32',
  ver: ipcRenderer.sendSync('app:ver'),
  invoke: (ch, ...a) => OK.has(ch) ? ipcRenderer.invoke(ch, ...a) : Promise.reject(new Error('blocked channel ' + ch)),
  on: (ch, cb) => { if (ON.has(ch)) ipcRenderer.on(ch, (_e, ...a) => cb(...a)); },
});
