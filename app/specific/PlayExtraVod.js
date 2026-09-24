/*
 * Copyright (c) 2017–present Felipe de Leon <fglfgl27@gmail.com>
 *
 * This file is part of SmartTwitchTV <https://github.com/fgl27/SmartTwitchTV>
 *
 * SmartTwitchTV is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SmartTwitchTV is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with SmartTwitchTV.  If not, see <https://github.com/fgl27/SmartTwitchTV/blob/master/LICENSE>.
 *
 */

//Only one vod plays at a time: PlayVod_*/ChannelVod_* always describe the vod in the main player,
//PlayExtraVod_Store describes it while it sits in the small one
var PlayExtraVod_InPP = false;
var PlayExtraVod_LoadId = 0;

function PlayExtraVod_NewStore() {
    return {
        data: [],
        vodId: '',
        channelId: '',
        channelLogin: '',
        channelName: '',
        channelLogo: '',
        logoId: 0,
        watchingTime: 0,
        channelPartner: null,
        title: '',
        game: '',
        language: '',
        createdAt: '',
        views: '',
        durationSeconds: 0,
        autoUrl: '',
        playlist: null,
        position: 0
    };
}

var PlayExtraVod_Store = PlayExtraVod_NewStore();

function PlayExtraVod_IsMixed() {
    return PlayExtra_PicturePicture && (PlayExtraVod_InPP || PlayVod_isOn);
}

function PlayExtraVod_Side() {
    if (!PlayExtraVod_IsMixed()) return -1;

    return PlayExtraVod_InPP ? 1 : 0;
}

function PlayExtraVod_StoreFromCell(cell, position) {
    var store = PlayExtraVod_NewStore();

    store.data = Main_Slice(cell);
    store.vodId = cell[7];
    store.channelId = cell[14];
    store.channelLogin = cell[6];
    store.channelName = cell[1];
    store.title = cell[10];
    store.game = cell[3];
    store.language = cell[9];
    store.createdAt = cell[2];
    store.views = cell[4];
    store.durationSeconds = parseInt(cell[15]);
    store.position = position;
    store.watchingTime = new Date().getTime();

    return store;
}

function PlayExtraVod_StoreFromMain() {
    //The ChannelVod_* globals are only filled by Main_OpenVodStart, the cell is always there
    var store = PlayExtraVod_StoreFromCell(Main_Slice(Main_values_Play_data), parseInt(OSInterface_gettime() / 1000));

    store.vodId = Main_values.ChannelVod_vodId;
    store.channelId = Main_values.Main_selectedChannel_id;
    store.channelLogin = Main_values.Main_selectedChannel;
    store.channelName = Main_values.Main_selectedChannelDisplayname;
    //Main_selectedChannelLogo holds vod cell index 15, the duration, never a logo
    store.channelPartner = Main_values.Main_selectedChannelPartner;
    store.durationSeconds = Play_DurationSeconds;
    store.autoUrl = PlayVod_autoUrl;
    store.playlist = PlayVod_playlist;

    if (ChannelVod_title) store.title = ChannelVod_title;
    if (ChannelVod_language) store.language = ChannelVod_language;
    if (ChannelVod_createdAt) store.createdAt = ChannelVod_createdAt;
    if (ChannelVod_views) store.views = ChannelVod_views;

    if (Main_A_equals_B(PlayExtraVod_Store.vodId, store.vodId) && PlayExtraVod_Store.watchingTime) {
        store.watchingTime = PlayExtraVod_Store.watchingTime;
    }

    return store;
}

function PlayExtra_PPKeyEnter() {
    if (UserLiveFeed_FeedPosX >= UserLiveFeedobj_UserVodPos) PlayExtraVod_KeyEnter();
    else PlayExtra_KeyEnter();
}

//Play_data keeps the last live stream while a vod plays, so it cannot name the main player
function PlayExtra_MainName() {
    return PlayVod_isOn ? Main_values.Main_selectedChannelDisplayname : Play_data.data[1];
}

function PlayExtra_DoSwitch() {
    if (PlayExtraVod_IsMixed()) {
        PlayExtraVod_Switch();

        return;
    }

    if (Main_IsOn_OSInterface) OSInterface_mSwitchPlayer();

    PlayExtra_SwitchPlayer();
}

function PlayExtraVod_KeyEnter() {
    PlayExtra_clear = true;

    if (Play_MaxInstances < 2) {
        Play_showWarningMiddleDialog(STR_4_WAY_MULTI_INSTANCES.replace('%x', Play_MaxInstances) + STR_PP_MODO, 3000);

        return;
    }

    if (Play_MultiEnable) {
        Play_showWarningMiddleDialog(STR_PP_VOD_ERROR, 2500);

        return;
    }

    if (PlayVod_isOn) {
        Play_showWarningMiddleDialog(STR_PP_VOD_ONLY_ONE, 2500);

        return;
    }

    var doc = Play_CheckLiveThumb(false, false);

    if (!doc) return;

    PlayExtraVod_Start(doc);
}

function PlayExtraVod_Start(cell) {
    PlayExtraVod_Store = PlayExtraVod_StoreFromCell(cell, PlayExtraVod_HistoryOffset(cell));
    PlayExtraVod_InPP = true;
    PlayExtra_PicturePicture = true;

    PlayExtra_data = JSON.parse(JSON.stringify(Play_data_base));
    PlayExtra_data.data = Main_Slice(cell);

    Main_innerHTML('chat_container_name_text1', STR_SPACE_HTML + PlayExtraVod_Store.channelName + STR_SPACE_HTML);

    Play_showBufferDialog();

    PlayExtraVod_LoadId = new Date().getTime();
    PlayHLS_GetPlayListAsync(false, PlayExtraVod_Store.vodId, PlayExtraVod_LoadId, null, PlayExtraVod_LoadResult);
}

function PlayExtraVod_HistoryOffset(cell) {
    if (!AddUser_UserIsSet()) return 0;

    var index = Main_history_Exist('vod', cell[7]);

    if (index < 0) return 0;

    var watched = Main_values_History_data[AddUser_UsernameArray[0].id].vod[index].watched;

    return watched > 0 ? watched : 0;
}

function PlayExtraVod_LoadResult(response) {
    //Called only by JAVA
    if (!PlayExtraVod_InPP || !response) return;

    var responseObj = JSON.parse(response);

    if (responseObj.checkResult < 1 || responseObj.checkResult !== PlayExtraVod_LoadId) return;

    if (responseObj.status === 200) {
        PlayExtraVod_Store.autoUrl = responseObj.url;
        PlayExtraVod_Store.playlist = responseObj.responseText;

        PlayExtraVod_StartPlayer();

        return;
    }

    PlayExtraVod_Fail(responseObj.status === 1 ? STR_IS_SUB_ONLY : STR_410_ERROR);
}

function PlayExtraVod_StartPlayer() {
    Play_HideBufferDialog();

    PlayExtra_SetPanel();
    PlayExtraVod_HideSmallChat();

    if (!Play_isFullScreen) OSInterface_mupdatesizePP(Play_isFullScreen);
    else OSInterface_mSwitchPlayerSize(Play_PicturePictureSize);

    if (Main_IsOn_OSInterface) {
        OSInterface_StartAuto(PlayExtraVod_Store.autoUrl, PlayExtraVod_Store.playlist, 2, PlayExtraVod_Store.position * 1000, 1);
    }

    UserLiveFeed_Hide();

    Main_Set_history('vod', PlayExtraVod_Store.data);

    Play_ResetStreamInfo();
    PlayExtra_UpdatePanel();
    Play_SetAudioIcon();
}

function PlayExtraVod_Fail(reason) {
    PlayExtraVod_ClearPP();
    PlayExtra_PicturePicture = false;
    PlayExtra_data = JSON.parse(JSON.stringify(Play_data_base));

    PlayExtra_UnSetPanel();
    Play_HideBufferDialog();
    Play_showWarningMiddleDialog(reason, 2500);
}

function PlayExtraVod_ClearPP() {
    if (PlayExtraVod_InPP) PlayExtraVod_SaveOffset();

    PlayExtraVod_InPP = false;
    PlayExtraVod_Store = PlayExtraVod_NewStore();
    PlayExtraVod_LoadId = 0;
}

function PlayExtraVod_EndedIsVod(doSwitch) {
    if (!PlayExtra_PicturePicture) return false;

    return doSwitch ? PlayVod_isOn : PlayExtraVod_InPP;
}

function PlayExtraVod_End(doSwitch, fail_type) {
    var reason = (doSwitch ? Main_values.Main_selectedChannelDisplayname : PlayExtraVod_Store.channelName) + STR_SPACE + STR_PP_VOD_ENDED;

    if (fail_type === 1) reason = STR_PLAYER_ERROR + STR_BR + STR_PLAYER_ERROR_MULTI;
    if (fail_type === 2) reason = STR_PLAYER_LAG_ERRO + STR_BR + STR_PLAYER_ERROR_MULTI;

    //The main player ended, hand the screen to the small one before closing it
    if (doSwitch) PlayExtra_DoSwitch();

    Play_showWarningMiddleDialog(reason, 2500 + (fail_type ? 2500 : 0));

    Play_CloseSmall();
}

function PlayExtraVod_UpdatePanelSide(pp) {
    var store = PlayExtraVod_Store;

    if (!store.data.length) return;

    PlayExtraVod_UpdateLogo();

    if (streamTitlePP[pp] !== store.title) {
        Main_innerHTML('stream_info_pp_title' + pp, store.title);
    }
    streamTitlePP[pp] = store.title;

    if (streamGamePP[pp] !== store.game) {
        Main_innerHTML('stream_info_pp_game' + pp, !store.game ? STR_SPACE_HTML : STR_PLAYING + store.game);
    }
    streamGamePP[pp] = store.game;

    if (streamViewersPP[pp] !== store.views) {
        Main_innerHTML('stream_info_pp_viewers' + pp, !store.views ? STR_SPACE_HTML : store.views + STR_SPACE_HTML + STR_VIEWS + ',');
    }
    streamViewersPP[pp] = store.views;
}

function PlayExtraVod_RefreshTimes(pp, dateNow) {
    var store = PlayExtraVod_Store;

    Main_textContentWithEle(Play_infoPPLiveTime[pp], store.createdAt);

    Main_textContentWithEle(Play_infoPPWatchingTime[pp], store.watchingTime ? STR_WATCHING + Play_timeMs(dateNow - store.watchingTime) : '');
}

function PlayExtraVod_UpdateLogo() {
    var store = PlayExtraVod_Store;
    var pp = PlayExtraVod_Side();

    if (pp < 0) return;

    var div = Play_partnerIcon(store.channelName, store.channelPartner, 1, store.language ? '[' + store.language.toUpperCase() + ']' : '');

    if (updateLogoPPDiv[pp] !== div) {
        Main_innerHTML('stream_info_pp_name' + pp, div);
    }
    updateLogoPPDiv[pp] = div;

    PlayExtra_SetPanelLogo(pp, store.vodId, store.channelLogo);

    if (store.channelLogo || store.logoId) return;

    store.logoId = new Date().getTime();

    BaseXmlHttpGet(
        Main_helix_api + 'users?id=' + store.channelId,
        PlayExtraVod_UpdateLogoResult,
        PlayExtraVod_UpdateLogoError,
        0,
        store.logoId,
        true
    );
}

function PlayExtraVod_UpdateLogoResult(responseText, key, ID) {
    if (PlayExtraVod_Store.logoId !== ID) return;

    var response = JSON.parse(responseText);

    if (!response.data || !response.data.length || !Main_A_equals_B(response.data[0].id, PlayExtraVod_Store.channelId)) return;

    PlayExtraVod_Store.channelPartner = response.data[0].broadcaster_type === 'partner';
    PlayExtraVod_Store.channelLogo = response.data[0].profile_image_url;

    PlayExtraVod_UpdateLogo();
}

function PlayExtraVod_UpdateLogoError(key, ID) {
    if (PlayExtraVod_Store.logoId === ID) PlayExtraVod_Store.logoId = 0;
}

function PlayExtraVod_SaveOffset() {
    if (!Main_IsOn_OSInterface || !PlayExtraVod_Store.vodId) return;

    var time = parseInt(OSInterface_gettimePP() / 1000);

    //The bridged position lags a switch by up to half a second and still reports the other player
    if (time < 1 || time >= PlayExtraVod_Store.durationSeconds) time = PlayExtraVod_Store.position;

    if (time > 0 && PlayExtraVod_Store.durationSeconds - 300 > time) {
        Main_history_UpdateVodClip(PlayExtraVod_Store.vodId, time, 'vod');
    }
}

function PlayExtraVod_Resume() {
    var saved = parseInt(OSInterface_getsavedtimePP() / 1000);

    PlayExtraVod_Store.position = saved > 0 ? saved : PlayExtraVod_Store.position;

    PlayExtraVod_LoadId = new Date().getTime();
    PlayHLS_GetPlayListAsync(false, PlayExtraVod_Store.vodId, PlayExtraVod_LoadId, null, PlayExtraVod_LoadResult);
}

function PlayExtraVod_HideSmallChat() {
    ChatLive_Clear(1);
    PlayExtra_HideChat();
}

function PlayExtraVod_Switch() {
    if (PlayVod_isOn) PlayExtraVod_SwitchToLiveMain();
    else PlayExtraVod_SwitchToVodMain();

    Play_ResetStreamInfo();
    PlayExtra_UpdatePanel();
    Play_SetAudioIcon();
    Main_SaveValues();
}

function PlayExtraVod_SwitchToLiveMain() {
    var live = JSON.parse(JSON.stringify(PlayExtra_data));
    var store = PlayExtraVod_StoreFromMain();

    PlayVod_SaveOffset();

    if (Main_IsOn_OSInterface) OSInterface_mSwitchPlayer();

    Play_data = live;
    PlayExtra_data = JSON.parse(JSON.stringify(Play_data_base));
    PlayExtra_data.data = Main_Slice(store.data);
    PlayExtra_data.watching_time = store.watchingTime;

    PlayExtraVod_Store = store;
    PlayExtraVod_InPP = true;

    PlayExtraVod_SwapVolumes();

    Chat_Clear();
    ChatLive_Close(1);
    PlayExtraVod_HideSmallChat();

    PlayExtraVod_EnterLiveMain();

    Main_innerHTML('chat_container_name_text0', STR_SPACE_HTML + Play_data.data[1] + STR_SPACE_HTML);
    Main_innerHTML('chat_container_name_text1', STR_SPACE_HTML + store.channelName + STR_SPACE_HTML);

    if (!Main_values.Play_ChatForceDisable) ChatLive_Init(0);
}

function PlayExtraVod_SwitchToVodMain() {
    var live = JSON.parse(JSON.stringify(Play_data));

    PlayExtraVod_Store.position = parseInt(OSInterface_gettimePP() / 1000);

    if (Main_IsOn_OSInterface) OSInterface_mSwitchPlayer();

    PlayExtra_data = live;
    //PlayExtraVod_Side reads this, it has to agree with the players before the mode is handed over
    PlayExtraVod_InPP = false;

    PlayExtraVod_SwapVolumes();

    ChatLive_Close(0);
    ChatLive_Clear(0);

    PlayExtraVod_EnterVodMain();

    Main_innerHTML('chat_container_name_text0', STR_SPACE_HTML + PlayExtraVod_Store.channelName + STR_SPACE_HTML);
    Main_innerHTML('chat_container_name_text1', STR_SPACE_HTML + PlayExtra_data.data[1] + STR_SPACE_HTML);

    if (!Main_values.Play_ChatForceDisable) {
        if (!Play_isFullScreen) {
            PlayExtra_ShowChat();
            ChatLive_Init(1);
        }

        Chat_offset = PlayExtraVod_Store.position;
        Chat_Init();
    }
}

//Tear the vod down without touching either player, the live stream in the small window keeps playing
//and the main one is about to be handed a new source by Play_Start
function PlayExtraVod_ReplaceVodMain() {
    PlayVod_UpdateHistory(Main_values.Main_Go, true);

    Main_ShowElementWithEle(Play_Controls_Holder);
    Main_ShowElementWithEle(Play_BottonIcons_Progress_PauseHolder);

    Play_OpenRewind = false;
    PlayVod_isOn = false;
    PlayClip_OpenAVod = true;
    PlayVod_qualities = [];
    PlayVod_playlist = null;

    Main_clearInterval(PlayVod_SaveOffsetId);
    Main_clearTimeout(PlayVod_WarnEndId);
    Main_clearTimeout(PlayClip_CheckIsLiveTimeoutId);

    Chat_Clear();

    PlayExtraVod_Store = PlayExtraVod_NewStore();

    UserLiveFeed_Hide();

    Play_ClearPlayer();
    PlayVod_ClearVod();
}

function PlayExtraVod_SwapVolumes() {
    var volume = Play_volumes[0];

    Play_volumes[0] = Play_volumes[1];
    Play_volumes[1] = volume;
}

function PlayExtraVod_EnterLiveMain() {
    Main_clearInterval(PlayVod_SaveOffsetId);
    Main_clearInterval(PlayVod_RefreshProgressBarrID);
    Main_clearTimeout(PlayVod_WarnEndId);
    Main_removeEventListener('keydown', PlayVod_handleKeyDown);
    PlayVod_previews_clear();
    PlayVod_ChaptersArray = [];
    PlayVod_muted_segments_value = null;
    Main_empty('inner_progress_bar_muted');

    PlayVod_isOn = false;
    Play_isOn = true;
    Play_Playing = true;
    Play_HasLive = false;
    Chat_title = '';
    Play_OpenRewind = false;

    Main_values.Main_selectedChannel = Play_data.data[6];
    Main_values.Main_selectedChannel_id = Play_data.data[14];
    Main_values.Main_selectedChannelDisplayname = Play_data.data[1];
    Main_values.Main_selectedChannelLogo = Play_data.data[9];

    //The stream kept playing in the small window, its watch time did not restart
    Play_DurationSeconds = 28;
    Main_textContentWithEle(Play_BottonIcons_Progress_Duration, Play_timeS(Play_DurationSeconds));

    Main_values.Play_WasPlaying = 1;

    //The seek step table is a global, vod mode swapped in its accelerating one
    PlayClip_SetProgressBarJumpers();

    Play_controls[Play_controlsChanelCont].setLabel(Play_data.data[1]);
    Play_controls[Play_controlsGameCont].setLabel(Play_data.data[3]);

    Main_PlayHandleKeyDown();
    Play_EndSet(1);
    Play_CheckFollow(Play_data.data[14]);
    Play_ShowPanelStatus(1);
    Play_getQualities(1, false);
    Play_SetControlsVisibilityPlayer(1);
    Play_updateStreamInfo();
}

function PlayExtraVod_EnterVodMain() {
    var store = PlayExtraVod_Store;

    Main_removeEventListener('keydown', Play_handleKeyDown);

    Main_values_Play_data = Main_Slice(store.data);
    Main_values.ChannelVod_vodId = store.vodId;
    Main_values.Main_selectedChannel = store.channelLogin;
    Main_values.Main_selectedChannel_id = store.channelId;
    Main_values.Main_selectedChannelDisplayname = store.channelName;
    Main_values.Main_selectedChannelPartner = store.channelPartner;

    if (store.channelLogo) Main_values.Main_selectedChannelLogo = store.channelLogo;

    ChannelVod_createdAt = store.createdAt;
    ChannelVod_views = store.views;
    ChannelVod_title = store.title;
    ChannelVod_game = store.game ? STR_STARTED + STR_PLAYING + store.game : '';
    ChannelVod_language = store.language;

    PlayVod_autoUrl = store.autoUrl;
    PlayVod_playlist = store.playlist;
    PlayVod_qualities = [];
    PlayVod_quality = 'Auto';
    PlayVod_qualityPlaying = PlayVod_quality;
    PlayVod_replay = false;
    PlayVod_ResumeTime = 0;
    PlayVod_OldTime = 0;
    PlayVod_currentTime = 0;
    PlayVod_ChaptersArray = [];
    PlayVod_muted_segments_value = null;
    PlayVod_previews_clear();

    Play_DurationSeconds = store.durationSeconds;
    Play_OpenRewind = false;
    Play_HasLive = false;
    Play_jumping = false;
    PlayVod_IsJumping = false;
    PlayVod_jump_max_step = Settings_value.vod_seek_max.defaultValue;
    Play_DefaultjumpTimers = Settings_jumpTimers;
    PlayVod_jumpSteps(Settings_value.vod_seek_min.defaultValue);

    Chat_title = ' VOD';
    Play_isOn = false;
    PlayVod_isOn = true;
    //Pause and seek only restart the vod chat while this is set
    PlayClip_HasVOD = true;
    ChannelVod_vodOffset = 0;

    Main_textContentWithEle(Play_BottonIcons_Progress_Duration, Play_timeS(Play_DurationSeconds));
    Main_ShowElementWithEle(Play_BottonIcons_Progress_PauseHolder);

    PlayVod_SaveOffsetId = Main_setInterval(PlayVod_SaveOffset, 60000, PlayVod_SaveOffsetId);

    Main_values.Play_WasPlaying = 2;

    Play_controls[Play_controlsChanelCont].setLabel(store.channelName);
    Play_controls[Play_controlsGameCont].setLabel(store.game);

    Main_PlayVodHandleKeyDown();
    Play_EndSet(2);
    PlayVod_get_vod_info();
    Play_ShowPanelStatus(2);
    Play_getQualities(2, false);
    Play_SetControlsVisibilityPlayer(2);
    OSInterface_getDuration('Play_UpdateDuration');
}
