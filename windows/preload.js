'use strict';
const { contextBridge, ipcRenderer } = require('electron');
const OK = new Set(['lib:load', 'lib:scan', 'lib:add', 'lib:folders', 'lib:remove', 'lyrics:get', 'lyrics:retry', 'mem:save', 'mem:thumb', 'mem:pick', 'mem:del', 'mem:show',
  'link:state', 'link:req', 'upd:check', 'upd:get', 'upd:cancel', 'upd:install', 'theme', 'sys:bt', 'alarm:show', 'alarm:armed', 'win:max', 'win:state']);
const ON = new Set(['lib:update', 'upd:prog', 'win:state', 'media:key', 'link:evt', 'link:cmd', 'link:cast']);
const SYNC = new Set(['link:srv', 'link:new', 'link:kick', 'link:mint', 'link:addout', 'link:delout']);
contextBridge.exposeInMainWorld('AirHost', {
  platform: 'win32',
  ver: ipcRenderer.sendSync('app:ver'),
  invoke: (ch, ...a) => OK.has(ch) ? ipcRenderer.invoke(ch, ...a) : Promise.reject(new Error('blocked channel ' + ch)),
  sync: (ch, ...a) => SYNC.has(ch) ? ipcRenderer.sendSync(ch, ...a) : null,
  on: (ch, cb) => { if (ON.has(ch)) ipcRenderer.on(ch, (_e, ...a) => cb(...a)); },
});
