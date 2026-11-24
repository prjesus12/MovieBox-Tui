sub init()
    m.backdrop = m.top.findNode("backdrop")
    m.titleLabel = m.top.findNode("title")
    m.subtitle = m.top.findNode("subtitle")
    m.seasonList = m.top.findNode("seasonList")
    m.grid = m.top.findNode("grid")
    m.episodesHeader = m.top.findNode("episodesHeader")
    m.seasonList.observeField("itemFocused", "onSeasonFocused")
    m.seasonList.observeField("itemSelected", "onSeasonSelected")
    m.grid.observeField("itemSelected", "onEpisodeSelected")
    m.currentSeasonIndex = -1
    m.pendingEpisode = 0
    m.autoPlayDone = false
end sub

sub onReady()
    item = m.top.item
    if item = invalid then return
    m.titleLabel.text = item.title
    m.backdrop.uri = item.backdrop
    seasons = m.top.seasons
    total = 0
    content = CreateObject("roSGNode", "ContentNode")
    startIndex = 0
    for i = 0 to seasons.Count() - 1
        s = seasons[i]
        total = total + s.maxEp
        child = content.createChild("ContentNode")
        child.title = "    Season " + s.se.ToStr()
        if s.se = m.top.startSeason then startIndex = i
    end for
    seasonWord = " seasons"
    if seasons.Count() = 1 then seasonWord = " season"
    m.subtitle.text = seasons.Count().ToStr() + seasonWord + "  •  " + total.ToStr() + " episodes"
    m.seasonList.content = content
    m.pendingEpisode = m.top.startEpisode
    m.seasonList.jumpToItem = startIndex
    showSeason(startIndex)
    startAutoPlay()
end sub

sub onSeasonFocused()
    showSeason(m.seasonList.itemFocused)
end sub

sub onSeasonSelected()
    m.grid.setFocus(true)
end sub

sub showSeason(index as Integer)
    seasons = m.top.seasons
    if index < 0 or index >= seasons.Count() then return
    if index = m.currentSeasonIndex then return
    m.currentSeasonIndex = index
    s = seasons[index]
    m.episodesHeader.text = "SEASON " + s.se.ToStr() + "  •  " + s.maxEp.ToStr() + " EPISODES"
    buildGrid(s)
end sub

sub buildGrid(season as Object)
    item = m.top.item
    entry = HistoryFind(item.sid)
    episodes = invalid
    if entry <> invalid then episodes = entry.episodes
    content = CreateObject("roSGNode", "ContentNode")
    for ep = 1 to season.maxEp
        child = content.createChild("ContentNode")
        child.HDPosterUrl = item.backdrop
        ' The screen reader speaks AUDIO_GUIDE_TEXT (or TITLE); these tiles had
        ' neither, so a whole episode grid was silent.
        child.title = "Episode " + ep.ToStr()
        child.addFields(ItemNodeFields({ se: season.se, ep: ep }))
        if episodes <> invalid then
            info = episodes[season.se.ToStr() + "x" + ep.ToStr()]
            if info <> invalid and info.d > 0 then
                ratio = info.p / info.d
                child.progress = ratio
                if ratio >= 0.94 then
                    child.subtitle = "Watched"
                else
                    child.subtitle = FormatClock(info.d - info.p) + " left"
                    child.resumeAt = info.p
                end if
            end if
        end if
        ' One assignment, after the progress pass, so there is no second write to
        ' trace and no dangling separator when there is no subtitle.
        guide = "Season " + season.se.ToStr() + ", episode " + ep.ToStr()
        if child.subtitle <> "" then guide = guide + ". " + child.subtitle
        child.AUDIO_GUIDE_TEXT = guide
    end for
    m.grid.content = content
    if m.pendingEpisode > 0 and m.pendingEpisode <= season.maxEp then
        m.grid.jumpToItem = m.pendingEpisode - 1
    end if
    m.pendingEpisode = 0
end sub

sub onEpisodeSelected()
    playEpisode(m.grid.itemSelected)
end sub

' One play action for both the tap and the Direct to Play hand-off, so the
' resume point keeps coming from the episode tile and nowhere else.
sub playEpisode(index as Integer)
    if index < 0 then return
    if m.grid.content = invalid then return
    child = m.grid.content.getChild(index)
    if child = invalid then return
    m.top.action = { type: "play", item: m.top.item, se: child.se, ep: child.ep, resume: child.resumeAt, seasons: m.top.seasons }
end sub

sub onFocusIn()
    if m.grid.content <> invalid and m.grid.content.getChildCount() > 0 then
        m.grid.setFocus(true)
    else
        m.seasonList.setFocus(true)
    end if
    startAutoPlay()
end sub

' Direct to Play (Roku Search `series`): MainScene adds this field only when the
' deep link already chose the episode, so the grid starts it instead of waiting
' for a tap. The grid has to exist before there is a resume point to read and the
' screen has to be in the stack before its action is observed, so both callbacks
' try; whichever lands second starts it, exactly once, and clears the field so a
' return from the player does not replay the deep link. An ordinary visit has no
' field and never reaches the play action here.
sub startAutoPlay()
    if m.autoPlayDone then return
    ' `autoPlay` is added at runtime by MainScene and only for a Direct to Play
    ' launch, so an ordinary visit must skip this instead of reading a field that
    ' does not exist. `startEpisode` is declared in the XML with a default.
    if not m.top.hasField("autoPlay") then return
    if m.top.getField("autoPlay") <> true then return
    if m.grid.content = invalid or m.grid.content.getChildCount() = 0 then return
    m.autoPlayDone = true
    m.top.setField("autoPlay", false)
    playEpisode(m.top.startEpisode - 1)
end sub

sub onRefresh()
    index = m.currentSeasonIndex
    if index < 0 then return
    focused = m.grid.itemFocused
    m.currentSeasonIndex = -1
    m.pendingEpisode = focused + 1
    showSeason(index)
end sub

function onKeyEvent(key as String, press as Boolean) as Boolean
    if not press then return false
    if key = "right" and m.seasonList.hasFocus() then
        m.grid.setFocus(true)
        return true
    else if key = "left" and m.grid.hasFocus() then
        m.seasonList.setFocus(true)
        return true
    end if
    return false
end function
