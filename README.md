This is a BitTorrent client, written to learn how to use akka and structure a program around actors.

The program is organized along the lines of what's suggested in Joe Armstrong's Programming Erlang, with seperate actors for each 'chunk' of state. The one exception is an actor that was split up in order to make unit testing easier.

There's a simple 1:1 or 1:many relationship at each level of the actor hierarchy. There's a single Storrent object for the whole program, which spawns a Torrent actor for each torrent file. Each Torrent actor spawns a Tracker actor to talk to the tracker, and one PeerConnection actor for each peer we want to connect to. Each PeerConnection actor spawns a single BTProtocol actor, which translates between akka ByteStrings and meaningful internal messages. Each BTProtocol actor creates a TCPClient actor to buffer up data until at least an entire frame has been received.
