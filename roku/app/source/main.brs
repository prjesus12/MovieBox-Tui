' MovieBox for Roku - application entry point.
sub Main(args as Dynamic)
    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.SetMessagePort(port)

    scene = screen.CreateScene("MainScene")
    screen.Show()

    launch = LaunchArgs(args)
    if launch <> invalid then scene.launchArgs = launch

    scene.ObserveField("exitApp", port)
    input = CreateObject("roInput")
    input.SetMessagePort(port)

    while true
        msg = Wait(0, port)
        msgType = Type(msg)
        if msgType = "roSGScreenEvent" then
            if msg.IsScreenClosed() then return
        else if msgType = "roSGNodeEvent" then
            if msg.GetField() = "exitApp" and msg.GetData() = true then
                screen.Close()
                return
            end if
        else if msgType = "roInputEvent" then
            launch = LaunchArgs(msg.GetInfo())
            if launch <> invalid then scene.launchArgs = launch
        end if
    end while
end sub

' Roku Search and voice send deep-link args with lowercase keys (`contentid`,
' `mediatype`), while manual launches use camelCase. Match keys case-insensitively
' so either spelling is read, and return invalid when there is no id to act on.
' Both the args at start-up and a roInput event while running go through here.
function LaunchArgs(raw as Dynamic) as Dynamic
    if raw = invalid then return invalid
    contentId = ""
    mediaType = ""
    for each key in raw
        value = ""
        if Type(raw[key]) = "String" then value = raw[key]
        name = LCase(key)
        if name = "contentid" then contentId = value
        if name = "mediatype" then mediaType = value
    end for
    if contentId = "" then return invalid
    return { contentId: contentId, mediaType: mediaType }
end function
