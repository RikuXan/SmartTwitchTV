/*
 *
 * Copyright 2026 Felipe de Leon fgl27@hotmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

//Twitch grants channel points and counts watch time from this presence mutation, not from
//playback itself, so without it a session earns nothing however long it plays
var Presence_url = 'https://gql.twitch.tv/gql';
var Presence_postMessage =
    '{"operationName":"ChannelPage_SetSessionStatus","variables":{"input":{"sessionID":"%s","availability":"ONLINE",' +
    '"activity":{"userID":"%c","type":"WATCHING"}}},"extensions":{"persistedQuery":{"version":1,' +
    '"sha256Hash":"8521e08af74c8cb5128e4bb99fa53b591391cb19492e65fb0489aeee2f96947f"}}}';

var Presence_tickMs = 60000;
var Presence_isOn = false;
//One activity per session, so every watched channel needs its own session like a browser tab does
var Presence_sessions = {};
var Presence_counts = {};
var Presence_ticks = 0;
//The Java bridge types the callback key as long, so requests are correlated by this sequence instead
var Presence_seq = 0;
var Presence_pending = {};
var Presence_logged = 0;

function Presence_Init() {
    if (Presence_isOn) return;

    Presence_isOn = true;
    OSInterface_PresenceLog('init');
    Presence_Tick();
}

function Presence_SessionId() {
    var id = '',
        i = 0;

    for (; i < 16; i++) id += Math.floor(Math.random() * 16).toString(16);

    return id;
}

function Presence_AddChannel(list, data) {
    var id = data && data.data && data.data.length ? data.data[14] : null;

    if (id && !Main_A_includes_B(list, id.toString())) list.push(id.toString());
}

function Presence_WatchedChannels() {
    var list = [],
        i = 0;

    if (!Play_isOn || Main_isStopped || PlayVod_isOn || PlayClip_isOn || !AddUser_UserIsSet()) return list;

    Presence_AddChannel(list, Play_data);

    if (PlayExtra_PicturePicture) Presence_AddChannel(list, PlayExtra_data);

    if (Play_MultiEnable) {
        for (; i < Play_MultiArray_length; i++) {
            if (Play_MultiArray[i]) Presence_AddChannel(list, Play_MultiArray[i]);
        }
    }

    return list;
}

function Presence_Tick() {
    var channels = Presence_WatchedChannels(),
        known = Object.keys(Presence_sessions),
        i = 0;

    for (i = 0; i < known.length; i++) {
        if (!Main_A_includes_B(channels, known[i])) {
            delete Presence_sessions[known[i]];
            delete Presence_counts[known[i]];
            OSInterface_PresenceLog('stopped watching channel_id=' + known[i]);
        }
    }

    if (Object.keys(Presence_pending).length > 20) Presence_pending = {};

    Presence_ticks++;
    if (Presence_ticks <= 3 || !channels.length) {
        OSInterface_PresenceLog(
            'tick ' +
                Presence_ticks +
                ' channels=' +
                channels.length +
                ' playOn=' +
                Play_isOn +
                ' stopped=' +
                Main_isStopped +
                ' vod=' +
                PlayVod_isOn +
                ' clip=' +
                PlayClip_isOn +
                ' userSet=' +
                AddUser_UserIsSet() +
                ' pip=' +
                PlayExtra_PicturePicture +
                ' multi=' +
                Play_MultiEnable
        );
    }

    for (i = 0; i < channels.length; i++) Presence_Send(channels[i]);

    Main_setTimeout(Presence_Tick, Presence_tickMs);
}

function Presence_Send(channelId) {
    if (!Presence_sessions[channelId]) {
        Presence_sessions[channelId] = Presence_SessionId();
        Presence_counts[channelId] = 0;
        OSInterface_PresenceLog('watching channel_id=' + channelId + ' session=' + Presence_sessions[channelId]);
    }

    Presence_counts[channelId]++;
    Presence_seq++;
    Presence_pending[Presence_seq] = channelId;

    FullxmlHttpGet(
        Presence_url,
        Main_OAuth_User_Headers,
        Presence_Result,
        Presence_Error,
        0,
        Presence_seq,
        'POST',
        Presence_postMessage.replace('%s', Presence_sessions[channelId]).replace('%c', channelId)
    );
}

function Presence_Channel(id) {
    var channelId = Presence_pending[id] ? Presence_pending[id] : '?';

    delete Presence_pending[id];

    return channelId;
}

function Presence_Result(responseObj, key, id) {
    var channelId = Presence_Channel(id);
    var text = responseObj && responseObj.responseText ? responseObj.responseText : '';
    var failed = !responseObj || responseObj.status !== 200 || Main_A_includes_B(text, '"error');

    if (failed || Presence_logged < 6) {
        Presence_logged++;
        OSInterface_PresenceLog(
            'ping channel_id=' +
                channelId +
                ' n=' +
                Presence_counts[channelId] +
                ' status=' +
                (responseObj ? responseObj.status : 'null') +
                ' body=' +
                text.substring(0, 400)
        );
    }
}

function Presence_Error(responseObj, key, id) {
    OSInterface_PresenceLog('ping channel_id=' + Presence_Channel(id) + ' failed status=' + (responseObj ? responseObj.status : 'null'));
}
