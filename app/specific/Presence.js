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
//Points and watch time are credited from this event, the playback telemetry beacon in the playlist
//carries no account identity and credits nothing
var Presence_spadeUrl = 'https://spade.twitch.tv/track';
var Presence_spadeHeaders = [['Content-Type', 'application/x-www-form-urlencoded; charset=UTF-8']];
var Presence_statusMessage =
    '{"operationName":"ChannelPage_SetSessionStatus","variables":{"input":{"sessionID":"%s","availability":"ONLINE",' +
    '"activity":{"userID":"%c","type":"WATCHING"}}},"extensions":{"persistedQuery":{"version":1,' +
    '"sha256Hash":"8521e08af74c8cb5128e4bb99fa53b591391cb19492e65fb0489aeee2f96947f"}}}';
var Presence_pointsMessage =
    '{"operationName":"ChannelPointsContext","variables":{"channelLogin":"%l","includeGoalTypes":["CREATOR","BOOST"]},' +
    '"extensions":{"persistedQuery":{"version":1,' +
    '"sha256Hash":"7fe050e3761eb2cf258d70ee1a21cbd76fa8cf3d7e7b12fc437e7029d446b5e3"}}}';
//VODs are credited by this authenticated progress mutation, their minute-watched analytics carry
//no account identity; it also drives Twitch's own resume position across devices
var Presence_vodMessage =
    '{"operationName":"updateUserViewedVideo","variables":{"input":{"userID":"%u","position":%p,"videoID":"%v",' +
    '"videoType":"VOD"}},"extensions":{"persistedQuery":{"version":1,' +
    '"sha256Hash":"bb58b1bd08a4ca0c61f2b8d323381a5f4cd39d763da8698f680ef1dfaea89ca1"}}}';
var Presence_dropsMessage =
    '{"operationName":"DropChannelCampaignsProgress","variables":{"channelID":"%c"},' +
    '"extensions":{"persistedQuery":{"version":1,' +
    '"sha256Hash":"a9778563e6f7ef878891dcc4049c263ec376c1ef5c1ad69395c3d8aef7f455e4"}}}';
var Presence_inventoryMessage =
    '{"operationName":"Inventory","variables":{},"extensions":{"persistedQuery":{"version":1,' +
    '"sha256Hash":"8337eb8541b314040b0edde0c09c5c7a2783ba1960aa9edfbf3bac16d0fec404"}}}';
var Presence_dropClaimMessage =
    '{"operationName":"DropsPage_ClaimDropRewards","variables":{"input":{"dropInstanceID":"%i"}},' +
    '"extensions":{"persistedQuery":{"version":1,' +
    '"sha256Hash":"a455deea71bdc9015b78eb49f4acfbce8baa7ccbedd28e549bb025bd0f751930"}}}';
var Presence_claimMessage =
    '{"operationName":"ClaimCommunityPoints","variables":{"input":{"channelID":"%c","claimID":"%i"}},' +
    '"extensions":{"persistedQuery":{"version":1,' +
    '"sha256Hash":"46aaeebe02c99afdf4fc97c7c0cba964124bf6b0af229395f1f6d1feed05b3d0"}}}';

var Presence_tickMs = 60000;
var Presence_pointsMs = 120000;
var Presence_inventoryMs = 300000;
var Presence_isOn = false;
var Presence_ticks = 0;
//One activity per session, so every watched channel needs its own session like a browser tab does
var Presence_sessions = {};
var Presence_counts = {};
var Presence_dueAt = {};
var Presence_pointsDueAt = {};
var Presence_dropsDueAt = {};
var Presence_dropWatched = {};
var Presence_logins = {};
var Presence_balances = {};
var Presence_claimed = {};
var Presence_dropClaimed = {};
var Presence_inventoryDueAt = 0;
var Presence_inventorySwept = false;
var Presence_broadcasts = {};
var Presence_gameNames = {};
var Presence_gameIds = {};
var Presence_infoDueAt = {};
var Presence_playSessions = {};
var Presence_minutes = {};
var Presence_watchDueAt = {};
var Presence_deviceId = null;
var Presence_positionMs = 0;
var Presence_vodDueAt = 0;
var Presence_vodId = null;
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

function Presence_SessionId(length) {
    var id = '',
        i = 0;

    for (; i < length; i++) id += Math.floor(Math.random() * 16).toString(16);

    return id;
}

function Presence_DeviceId() {
    if (!Presence_deviceId) Presence_deviceId = Presence_SessionId(32);

    return Presence_deviceId;
}

function Presence_AddChannel(list, data) {
    if (!data || !data.data || !data.data.length) return;

    var id = data.data[14];

    if (!id || Main_A_includes_B(list, id.toString())) return;

    list.push(id.toString());
    if (data.data[6]) Presence_logins[id.toString()] = data.data[6];

    //Only a seed for the first event, the player refreshes this array on panel open alone and
    //Presence_Info owns the values from then on
    if (data.data[7] && !Presence_broadcasts[id.toString()]) Presence_broadcasts[id.toString()] = data.data[7];
    if (data.data[3] && !Presence_gameNames[id.toString()]) Presence_gameNames[id.toString()] = data.data[3];
    if (data.data[18] && !Presence_gameIds[id.toString()]) Presence_gameIds[id.toString()] = data.data[18].toString();
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
        now = new Date().getTime(),
        i = 0;

    for (i = 0; i < known.length; i++) {
        if (!Main_A_includes_B(channels, known[i])) {
            delete Presence_sessions[known[i]];
            delete Presence_counts[known[i]];
            delete Presence_dueAt[known[i]];
            delete Presence_pointsDueAt[known[i]];
            delete Presence_dropsDueAt[known[i]];
            delete Presence_dropWatched[known[i]];
            delete Presence_gameNames[known[i]];
            delete Presence_gameIds[known[i]];
            delete Presence_infoDueAt[known[i]];
            delete Presence_balances[known[i]];
            delete Presence_watchDueAt[known[i]];
            delete Presence_playSessions[known[i]];
            delete Presence_minutes[known[i]];
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

    Presence_Vod(now);

    if (channels.length && !Presence_inventorySwept) {
        Presence_inventorySwept = true;
        Presence_Inventory();
    }

    for (i = 0; i < channels.length; i++) {
        if (!Presence_sessions[channels[i]] || now >= Presence_dueAt[channels[i]]) Presence_Status(channels[i]);

        if (Presence_logins[channels[i]] && (!Presence_pointsDueAt[channels[i]] || now >= Presence_pointsDueAt[channels[i]])) {
            Presence_Points(channels[i]);
        }

        if (Presence_logins[channels[i]] && (!Presence_watchDueAt[channels[i]] || now >= Presence_watchDueAt[channels[i]])) {
            Presence_Watch(channels[i]);
        }

        if (!Presence_dropsDueAt[channels[i]] || now >= Presence_dropsDueAt[channels[i]]) Presence_Drops(channels[i]);

        if (!Presence_infoDueAt[channels[i]] || now >= Presence_infoDueAt[channels[i]]) Presence_Info(channels[i]);
    }

    Main_setTimeout(Presence_Tick, Presence_tickMs);
}

function Presence_Vod(now) {
    if (!PlayVod_isOn || Main_isStopped || !AddUser_UserIsSet()) {
        Presence_vodId = null;
        return;
    }

    var vodId = Main_values.ChannelVod_vodId;
    var position = Math.floor(Presence_positionMs / 1000);

    if (!vodId || position < 1) return;

    if (vodId !== Presence_vodId) {
        Presence_vodId = vodId;
        Presence_vodDueAt = 0;
        OSInterface_PresenceLog('watching vod=' + vodId);
    }

    if (now < Presence_vodDueAt) return;

    Presence_vodDueAt = now + Presence_tickMs;

    Presence_Post(
        vodId,
        'vod',
        Presence_vodMessage
            .replace('%u', AddUser_UsernameArray[0].id)
            .replace('%p', position)
            .replace('%v', vodId)
    );
}

function Presence_Post(channelId, kind, postMessage, url, headers) {
    Presence_seq++;
    Presence_pending[Presence_seq] = {channel: channelId, kind: kind};

    FullxmlHttpGet(
        url ? url : Presence_url,
        headers ? headers : Main_OAuth_User_Headers,
        Presence_Result,
        Presence_Error,
        0,
        Presence_seq,
        'POST',
        postMessage
    );
}

function Presence_Watch(channelId) {
    Presence_watchDueAt[channelId] = new Date().getTime() + Presence_tickMs;

    if (!Presence_playSessions[channelId]) Presence_playSessions[channelId] = Presence_SessionId(32);

    Presence_minutes[channelId] = Presence_minutes[channelId] ? Presence_minutes[channelId] + 1 : 1;

    //Drop campaigns are keyed by game, an event without the category credits points but no drop
    //progress, and spade only reads the event out of a batch array
    var events = [
        {
            event: 'minute-watched',
            properties: {
                broadcast_id: Presence_broadcasts[channelId] ? Presence_broadcasts[channelId] : '',
                channel: Presence_logins[channelId],
                channel_id: channelId,
                client_app: 'twilight',
                client_time: new Date().toISOString(),
                device_id: Presence_DeviceId(),
                game: Presence_gameNames[channelId] ? Presence_gameNames[channelId] : '',
                game_id: Presence_gameIds[channelId] ? Presence_gameIds[channelId] : '',
                hidden: false,
                is_live: true,
                live: true,
                location: 'channel',
                logged_in: true,
                login: AddUser_UsernameArray[0].name,
                minutes_logged: Presence_minutes[channelId],
                muted: false,
                platform: 'web',
                play_session_id: Presence_playSessions[channelId],
                player: 'site',
                player_type: 'site',
                user_id: parseInt(AddUser_UsernameArray[0].id, 10)
            }
        }
    ];

    Presence_Post(
        channelId,
        'watch',
        'data=' + encodeURIComponent(btoa(JSON.stringify(events))),
        Presence_spadeUrl,
        Presence_spadeHeaders
    );
}

function Presence_Status(channelId) {
    if (!Presence_sessions[channelId]) {
        Presence_sessions[channelId] = Presence_SessionId(16);
        Presence_counts[channelId] = 0;
        OSInterface_PresenceLog(
            'watching channel_id=' +
                channelId +
                ' session=' +
                Presence_sessions[channelId] +
                ' broadcast=' +
                (Presence_broadcasts[channelId] ? Presence_broadcasts[channelId] : 'none') +
                ' game=' +
                (Presence_gameNames[channelId] ? Presence_gameNames[channelId] : 'none') +
                '/' +
                (Presence_gameIds[channelId] ? Presence_gameIds[channelId] : 'none')
        );
    }

    Presence_counts[channelId]++;
    Presence_dueAt[channelId] = new Date().getTime() + Presence_tickMs;

    Presence_Post(channelId, 'status', Presence_statusMessage.replace('%s', Presence_sessions[channelId]).replace('%c', channelId));
}

function Presence_Points(channelId) {
    Presence_pointsDueAt[channelId] = new Date().getTime() + Presence_pointsMs;

    Presence_Post(channelId, 'points', Presence_pointsMessage.replace('%l', Presence_logins[channelId]));
}

function Presence_Info(channelId) {
    Presence_infoDueAt[channelId] = new Date().getTime() + Presence_pointsMs;

    //The bridge types the callback key as long, a string channel id comes back as 0
    BaseXmlHttpGet(Main_helix_api + 'streams?user_id=' + channelId, Presence_InfoResult, noop_fun, parseInt(channelId, 10), 0, true);
}

function Presence_InfoResult(response, channelId, id) {
    var stream = null;

    try {
        var obj = JSON.parse(response);

        stream = obj && obj.data && obj.data.length ? obj.data[0] : null;
    } catch (e) {}

    if (!stream || !Presence_logins[channelId]) return;

    if (stream.game_name && stream.game_name !== Presence_gameNames[channelId]) {
        OSInterface_PresenceLog(
            'game channel_id=' +
                channelId +
                ' was=' +
                (Presence_gameNames[channelId] ? Presence_gameNames[channelId] : 'none') +
                ' now=' +
                stream.game_name +
                '/' +
                stream.game_id
        );
        Presence_gameNames[channelId] = stream.game_name;
    }

    if (stream.game_id) Presence_gameIds[channelId] = stream.game_id.toString();

    if (stream.id && stream.id.toString() !== Presence_broadcasts[channelId]) {
        OSInterface_PresenceLog('broadcast channel_id=' + channelId + ' was=' + (Presence_broadcasts[channelId] ? Presence_broadcasts[channelId] : 'none') + ' now=' + stream.id);
        Presence_broadcasts[channelId] = stream.id.toString();
    }
}

function Presence_Drops(channelId) {
    Presence_dropsDueAt[channelId] = new Date().getTime() + Presence_pointsMs;

    Presence_Post(channelId, 'drops', Presence_dropsMessage.replace('%c', channelId));
}

function Presence_Inventory() {
    var now = new Date().getTime();

    if (now < Presence_inventoryDueAt) return;

    Presence_inventoryDueAt = now + Presence_inventoryMs;

    Presence_Post('inventory', 'inventory', Presence_inventoryMessage);
}

function Presence_ReadInventory(text) {
    var campaigns = null,
        drops,
        drop,
        claimed = 0,
        pending = 0,
        seen = 0,
        i = 0,
        j = 0;

    var inventory = null;

    try {
        var obj = JSON.parse(text);

        inventory = obj && obj.data && obj.data.currentUser ? obj.data.currentUser.inventory : null;
    } catch (e) {}

    if (!inventory) {
        OSInterface_PresenceLog('inventory unreadable body=' + text.substring(0, 200));
        return;
    }

    campaigns = inventory.dropCampaignsInProgress ? inventory.dropCampaignsInProgress : [];

    for (i = 0; i < campaigns.length; i++) {
        drops = campaigns[i].timeBasedDrops ? campaigns[i].timeBasedDrops : [];

        for (j = 0; j < drops.length; j++) {
            drop = drops[j].self;
            seen++;

            if (!drop) continue;

            if (!drop.dropInstanceID) {
                if (!drop.isClaimed) pending++;
                continue;
            }

            if (drop.isClaimed) continue;

            Presence_ClaimDrop(campaigns[i].name ? campaigns[i].name : campaigns[i].id, drops[j].name ? drops[j].name : drops[j].id, drop.dropInstanceID);
            claimed++;
        }
    }

    OSInterface_PresenceLog('inventory campaigns=' + campaigns.length + ' drops=' + seen + ' unearned=' + pending + ' claiming=' + claimed);

    if (claimed) Presence_inventoryDueAt = new Date().getTime() + Presence_tickMs;
}

function Presence_ClaimDrop(campaign, drop, instanceId) {
    if (Presence_dropClaimed[instanceId]) return;

    Presence_dropClaimed[instanceId] = true;
    if (Object.keys(Presence_dropClaimed).length > 50) Presence_dropClaimed = {};

    OSInterface_PresenceLog('claiming drop campaign=' + campaign + ' drop=' + drop + ' instance=' + instanceId);

    Presence_Post('drop', 'dropclaim', Presence_dropClaimMessage.replace('%i', instanceId));
}

function Presence_Claim(channelId, claimId) {
    if (Presence_claimed[claimId]) return;

    Presence_claimed[claimId] = true;
    if (Object.keys(Presence_claimed).length > 50) Presence_claimed = {};

    OSInterface_PresenceLog('claiming bonus channel_id=' + channelId + ' claim=' + claimId);

    Presence_Post(channelId, 'claim', Presence_claimMessage.replace('%c', channelId).replace('%i', claimId));
}

//Twitch answers every ping with setAgainInSeconds, following it keeps the app on the server's
//own presence cadence instead of a guessed one
function Presence_NextDue(text) {
    var seconds = 300;

    try {
        var obj = JSON.parse(text);

        if (obj && obj.data && obj.data.setSessionStatus && obj.data.setSessionStatus.setAgainInSeconds) {
            seconds = obj.data.setSessionStatus.setAgainInSeconds;
        }
    } catch (e) {}

    if (seconds < 60) seconds = 60;
    else if (seconds > 600) seconds = 600;

    return new Date().getTime() + seconds * 1000;
}

function Presence_ReadPoints(channelId, text) {
    var points = null;

    try {
        var obj = JSON.parse(text);

        points =
            obj && obj.data && obj.data.community && obj.data.community.channel && obj.data.community.channel.self
                ? obj.data.community.channel.self.communityPoints
                : null;
    } catch (e) {}

    if (!points) {
        OSInterface_PresenceLog('points channel_id=' + channelId + ' unreadable body=' + text.substring(0, 300));
        return;
    }

    if (points.balance !== Presence_balances[channelId]) {
        OSInterface_PresenceLog(
            'points channel_id=' +
                channelId +
                ' balance=' +
                points.balance +
                (Presence_balances[channelId] !== undefined ? ' gained=' + (points.balance - Presence_balances[channelId]) : '')
        );
        Presence_balances[channelId] = points.balance;
    }

    if (points.availableClaim && points.availableClaim.id) Presence_Claim(channelId, points.availableClaim.id);
}

function Presence_ReadDrops(channelId, text) {
    var campaigns = null,
        groups,
        logged,
        i = 0,
        j = 0;

    try {
        var obj = JSON.parse(text);

        campaigns = obj && obj.data ? obj.data.channelDropCampaignsProgress : null;
    } catch (e) {}

    if (Main_A_includes_B(text, '"challenge"')) {
        OSInterface_PresenceLog('drop channel_id=' + channelId + ' challenged body=' + text.substring(0, 200));
        return;
    }

    if (!campaigns || !campaigns.length) {
        if (Presence_dropWatched[channelId] === undefined) {
            Presence_dropWatched[channelId] = -1;
            OSInterface_PresenceLog('drop channel_id=' + channelId + ' campaigns=0');
        }

        return;
    }

    for (i = 0; i < campaigns.length; i++) {
        groups = campaigns[i].rewardGroups ? campaigns[i].rewardGroups : [];
        logged = false;

        for (j = 0; j < groups.length; j++) {
            if (!groups[j] || !groups[j].self) continue;

            Presence_LogDrop(channelId, campaigns[i], groups[j], groups[j].self);
            logged = true;
        }

        if (!logged && campaigns[i].self) Presence_LogDrop(channelId, campaigns[i], campaigns[i], campaigns[i].self);
    }
}

function Presence_LogDrop(channelId, campaign, group, self) {
    var key = channelId + '|' + (group && group.id ? group.id : campaign.id),
        required =
            group && group.progressCriteria && group.progressCriteria.requirements && group.progressCriteria.requirements.minutesWatched
                ? group.progressCriteria.requirements.minutesWatched
                : 0,
        watched = self && self.currentMinutesWatched ? self.currentMinutesWatched : 0,
        previous = Presence_dropWatched[key];

    if (Object.keys(Presence_dropWatched).length > 50) Presence_dropWatched = {};

    Presence_dropWatched[key] = watched;

    OSInterface_PresenceLog(
        'drop channel_id=' +
            channelId +
            ' broadcast=' +
            (Presence_broadcasts[channelId] ? Presence_broadcasts[channelId] : 'none') +
            ' campaign=' +
            campaign.name +
            ' watched=' +
            watched +
            '/' +
            required +
            (previous !== undefined ? ' gained=' + (watched - previous) : '') +
            ' status=' +
            (self ? self.status : 'none')
    );

    //Twitch's own client treats the minute count reaching the requirement as earned, the status
    //string lags behind it
    if (!self || self.status === 'CLAIMED' || self.status === 'FULFILLED') return;

    if ((required > 0 && watched >= required) || (self.status && self.status !== 'IN_PROGRESS')) Presence_Inventory();
}

function Presence_Result(responseObj, key, id) {
    var request = Presence_pending[id] ? Presence_pending[id] : {channel: '?', kind: '?'};

    delete Presence_pending[id];

    var text = responseObj && responseObj.responseText ? responseObj.responseText : '';
    var status = responseObj ? responseObj.status : 0;
    var failed = status < 200 || status > 299 || Main_A_includes_B(text, '"error');

    if (failed || request.kind === 'claim' || request.kind === 'dropclaim' || Presence_logged < 8) {
        Presence_logged++;
        OSInterface_PresenceLog(
            request.kind +
                ' channel_id=' +
                request.channel +
                ' status=' +
                (responseObj ? responseObj.status : 'null') +
                ' body=' +
                text.substring(0, 400)
        );
    }

    if (failed) return;

    if (request.kind === 'status') Presence_dueAt[request.channel] = Presence_NextDue(text);
    else if (request.kind === 'points') Presence_ReadPoints(request.channel, text);
    else if (request.kind === 'drops') Presence_ReadDrops(request.channel, text);
    else if (request.kind === 'inventory') Presence_ReadInventory(text);
}

function Presence_Error(responseObj, key, id) {
    var request = Presence_pending[id] ? Presence_pending[id] : {channel: '?', kind: '?'};

    delete Presence_pending[id];

    OSInterface_PresenceLog(request.kind + ' channel_id=' + request.channel + ' failed status=' + (responseObj ? responseObj.status : 'null'));
}
