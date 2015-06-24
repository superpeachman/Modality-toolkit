MIDIMKtlDevice : MKtlDevice {

	classvar <allMsgTypes, msgTypeKeys;

	classvar <protocol = \midi;
	classvar <initialized = false;

	// MIDI-specific address identifiers
	var <srcID /*Int*/, <source /*MIDIEndPoint*/;
	var <dstID /*Int*/, <destination /*MIDIEndPoint*/, <midiOut /*MIDIOut*/;

	// an action that is called every time a midi message comes in
	// .value(type, src, chan, num/note, value/vel)

	// optimisation for fast lookup in one flat dict:
	var <midiKeyToElemDict;    // find element by e.g. midiCCKey

	// an action that is called every time a midi message comes in
	// .value(type, src, chan, num/note, value/vel)
	var <>midiRawAction;

	// a dictionary of actions for incoming MIDI messages by type
	var <global;
	var <responders; // the MIDIFuncs responding to each protocol
	var <msgTypes;	// the msgTypes for which this MKtl needs MIDIfuncs

	*initClass {
		allMsgTypes = #[
			\noteOn, \noteOff, \noteOnOff, \cc, \control, \polyTouch, \polytouch,
			\touch, \bend, \program,
			\midiClock, \start, \stop, \continue, \reset,
			\allNotesOff
		];

		msgTypeKeys = (
			\cc: "c_%_%",
			\control: "c_%_%",
			\noteOn: "non_%_%",
			\noteOff: "nof_%_%",
			\polyTouch: "pt_%_%",
			\polytouch: "pt_%_%",
			\bend: "b_%",
			\touch: "t_%",
			\program: "p_%",
			\allNotesOff: "all_nof_%",
		);
	}

	closeDevice {
		destination.notNil.if {
			if ( thisProcess.platform.name == \linux ) {
				midiOut.disconnect( MIDIClient.destinations.indexOf(destination) )
			};
			midiOut = nil;
		};
		source = nil;
		destination = nil;
	}

	prSetSrcID { |argID| srcID = argID; }

	// open all ports
	*initDevices { |force = false|

		if ( initialized && { force.not } ){ ^this; };

		// workaround for inconsistent behaviour between linux and osx
		if ( MIDIClient.initialized and: (thisProcess.platform.name == \linux) ){
			MIDIClient.disposeClient;
			MIDIClient.init;
		};
		// broken MIDI init on osx
		if ( thisProcess.platform.name == \osx and: Main.versionAtMost( 3,6 ) ){
			"next time you recompile the language, reboot the interpreter"
			"\n instead to get MIDI working again.".warn;
		};

		MIDIIn.connectAll;
		MKtlLookup.addAllMIDI;
		initialized = true;
	}

	*find { |post = true|
		this.initDevices( true );

		if ( MIDIClient.sources.isEmpty and: MIDIClient.destinations.isEmpty ) {
			"// MIDIMKtl did not find any sources or destinations - "
			"\n// you may want to connect some first.".inform;
			^this
		};

		if ( post ){
			this.postPossible;
		};
	}

	// display all ports in readable fashion, copy/paste-able directly
	*postPossible {
		var postables = MKtlLookup.allFor(\midi);
		if (postables.size == 0) {
			"No MIDI devices available.".inform;
			^this;
		};

		"\n// Available MIDIMKtls: ".postln;
		"// MKtl(name, filename);  // *[ midi device, portname, uid]\n".postln;
		postables.keysValuesDo { |key, infodict|
			var endPoint = infodict.deviceInfo;
			var postList = endPoint.collect({ |ep| [ep.device.cs, ep.name.cs, ep.uid] });
			var filename = MKtlDesc.filenameForIDInfo(infodict.idInfo);

			filename = if (filename.isNil) { "" } { "," + quote(filename) };
			filename = if (filename.isNil) { "" } { "," + quote(filename) };
			"MKtl('nameMe', %);		// %\n".postf(key.cs, postList.unbubble);
		};
	}

	// create with a uid, or access by name
	// FIXME: not sure about how t get destUID to work again in the refactor
	// *new { |name, srcUID, destUID, parentMKtl|
	*new { |name, idInfo, parentMKtl|

		var lookupInfo = parentMKtl.lookupInfo;
		var foundInfo, foundSources, foundDestinations;
		var newDev;

		idInfo = idInfo ?? {
			parentMKtl.lookupInfo.idInfo ?? {
				parentMKtl.desc.idInfo
		} };

		if (idInfo.isNil) {
			inform("MIDIMKtlDevice.new: cannot create new without idInfo");
			^this;
		};

		foundInfo = MKtlLookup.findByIDInfo(idInfo);
		if (foundInfo.size > 1) {
			"multiple MIDIMktls of same name not supported yet - taking first.".postln;
		};

		foundInfo = foundInfo.asArray.first;

		foundSources = foundInfo[\srcDevice].postln;
		foundDestinations = foundInfo[\destDevice].postln;

		newDev = super.basicNew(name, lookupInfo.idInfo, parentMKtl );
		newDev.initMIDIMKtl(name, foundSources, foundDestinations );
		newDev.initElements;
		newDev.makeRespFuncs(newDev.srcID);
		^newDev
	}

	/// ----(((((----- EXPLORING ---------

	exploring {
		^(MIDIExplorer.observedSrcID == srcID );
	}

	explore { |mode=true|
		if ( mode ){
			"Using MIDIExplorer. (see its Helpfile for Details)\n"
			"\n"
			"MIDIExplorer started. Wiggle all elements of your controller then\n"
			"\tMKtl(%).explore(false);\n"
			"\tMKtl(%).createDescriptionFile;\n"
			.format(name, name).inform;

			MIDIExplorer.start(this.srcID);
		} {
			MIDIExplorer.stop(this.srcID);
			"MIDIExplorer stopped.".inform;
		}
	}

	createDescriptionFile {
		MIDIExplorer.openDoc;
	}

	/// --------- EXPLORING -----)))))---------

	initElements {
	//	"initElements".postln;
		if ( mktl.elementsDict.isNil or: {
			mktl.elementsDict.isEmpty
		}) {
			warn(mktl + "has no elements:\n" +
				mktl.elementsDict.asCompileString;
			);
			^this;
		};
		msgTypes = mktl.desc.fullDesc[\msgTypesUsed];
		this.prepareLookupDicts;
	}

	// nothing here yet, but needed
	initCollectives {

	}

	initMIDIMKtl { |argName, argSource, argDestination|
		// [argName, argSource, argDestination].postln;
		name = argName;
		// "initMIDIMKtl".postln;
		source = argSource;

	// FIXME later: full support of multi-uids everywhere
		source.notNil.if { srcID = source.asArray.collect(_.uid).unbubble };

	// // destination is optional
		destination = argDestination;
		destination.notNil.if { dstID = destination.asArray.collect(_.uid).unbubble };

		// was simpler:
		// source.notNil.if { srcID = source.uid };
		// destination.notNil.if { dstID = destination.uid; };

		destination.notNil.if {
 			if ( thisProcess.platform.name == \linux ) {
				midiOut = MIDIOut( 0 );
				midiOut.connect( MIDIClient.destinations.indexOf(destination) )
			} {
				midiOut = MIDIOut( MIDIClient.destinations.indexOf(destination), dstID );
			};

			// set latency to zero as we assume to have controllers
			// rather than synths connected to the device.
			midiOut.latency = 0;
		};

		this.initCollectives;
	}

	makeHashKey { |elemDesc, elem|

		var msgType = elemDesc[\midiMsgType];
		var hashKeys;

		if( allMsgTypes.includes(msgType).not ) {
			warn("% has unsupported \\midiMsgType: %".format(elem, msgType));
			^this
		};

		// this could be an array in desc already!
		if (msgType == \noteOnOff) { msgType = [\noteOn, \noteOff] };
		hashKeys = msgType.asArray.collect { |type|
			MIDIMKtlDevice.makeMsgKey(type, elemDesc[\midiChan], elemDesc[\midiNum]);
		};

			^hashKeys
	}

	// utilities for fast lookup of elements in elementsDict

	*makeMsgKey { |msgType, chan, num|
		var temp = msgTypeKeys[msgType];
		if (temp.isNil) {
			"Message type % not supported.".inform;
			^nil
		} {
			^temp.format(chan, num).asSymbol;
		}
	}

	// // not used, likely gone?
	// *ccKeyToChanCtl { |ccKey| ^ccKey.asString.drop(2).split($_).asInteger }
	// *noteKeyToChanNote { |noteKey| ^noteKey.asString.drop(2).split($_).asInteger }

	// was 'plumbing'
	prepareLookupDicts {
		var elementsDict = mktl.elementsDict;
		midiKeyToElemDict = ();

		if (elementsDict.isNil) {
			warn("% has no elementsDict?".format(mktl));
			^this
		};

		elementsDict.do { |elem|
			var elemDesc = elem.elemDesc;
			var midiKeys = this.makeHashKey( elemDesc, elem );

			// set the inputs only; outputs can use elemDesc directly
			if ( [nil, \in, \inout].includes(elemDesc[\ioType])) {
				// element has specific description for the input
				midiKeys.do { |key|
					midiKeyToElemDict.put(*[key, elem]);
				};
			};
		};
	}


	////////// make the responding MIDIFuncs \\\\\\\
	// only make the ones that are needed once,
	// and activate/deactivate them

	// channel bend, touch, program, ...
	makeChanMsgMIDIFunc { |typeKey|

		if (typeKey == \cc) { typeKey = \control };
		// "makeChanMsgMIDIFunc for % \n".postf(typeKey);

		responders.put(typeKey,
			MIDIFunc({ |value, chan, src|
				var hash = MIDIMKtlDevice.makeMsgKey(typeKey, chan);
				var el = midiKeyToElemDict[hash];

				 // do global actions first
				midiRawAction.value(typeKey, src, chan, value);
				global[typeKey].value(chan, value);

				if (el.notNil) {
					el.deviceValueAction_(value);
					if(traceRunning) {
						MIDIMKtlDevice.postMsgTrace(mktl, el, el.value,
						typeKey, value, nil, chan, src);
					};
				} {
					if (traceRunning) {
						MIDIMKtlDevice.postMsgNotFound(mktl, typeKey,
						value, nil, chan, src);
					};
				}

			}, msgType: typeKey, srcID: srcID).permanent_(true);
		);
	}

	// chan & note or cc; noteOn, noteOff, cc, polyTouch
	makeChanNumMsgMIDIFunc { |typeKey|

		if (typeKey == \cc) { typeKey = \control };
		if (typeKey == \polyTouch) { typeKey = \polytouch };
		// "makeChanNumMsgMIDIFunc for % \n".postf(typeKey);

		responders.put(typeKey,
			MIDIFunc({ |value, num, chan, src|
				var hash = MIDIMKtlDevice.makeMsgKey(typeKey, chan, num);
				var el = midiKeyToElemDict[hash];

				 // do global actions first
				midiRawAction.value(typeKey, src, chan, num, value);
				global[typeKey].value(chan, num, value);

				if (el.notNil) {
					el.deviceValueAction_(value);
					if(traceRunning) {
						MIDIMKtlDevice.postMsgTrace(mktl, el, el.value,
						typeKey, value, num, chan, src);
					};
				} { // element is nil
					if (traceRunning) {
						MIDIMKtlDevice.postMsgNotFound(mktl, typeKey,
						value, num, chan, src);
					};
				}

			}, msgType: typeKey, srcID: srcID).permanent_(true);
		);
	}

	*postMsgNotFound { |mktl, msgType, value, num, chan, src|
		var numStr = if (num.notNil) { "midiNum: %, ".format(num) } { "" };

		"% : unknown % element found at % midiChan %.\n"
		"\tPlease add it to the description file. E.g. for a button:"
		"<bt>: (midiMsgType: %, type: <'button'>,"
		" midiChan: %, %spec: <'midiBut'>, mode: <'push'>)\n\n"
		.format(mktl, msgType.cs, numStr, chan, msgType.cs, chan, numStr).inform;
	}

	*postMsgTrace { |mktl, elemName, elemVal, msgType, value, num, chan, src|
		var numStr = "";
		if (num.notNil) {
			numStr = msgType.switch(
				\cc, "ccNum: %, ",
				\control, "ccNum: %, ",
				\noteOn, "vel: %, ",
				\noteOff, "vel: %, ",
				\polyTouch, "touchVal: %, ",
				\polytouch, "touchVal: %, "
			)
			.format(num)
		} { "" };

		"%: %: %\n"
		"  type: %, %midiChan: %, src: %, val: %"
		.format(mktl, elemName.cs, elemVal.asStringPrec(3),
			msgType.cs, numStr, chan, src, value).postln;
	}

	// for the simpler chan based messages, collect chans,
	// if single chan, use in midifunc,
	// else match inside MIDIfunc
	// same would work with classes
	findChans { |typeKey|
		var myElems = mktl.elementsDict.select { |el|
			el.elemDesc[\midiMsgType] == typeKey;
		};
		var myChans = myElems.collect { |el|
			el.elemDesc[\midiChan];
		}.asArray.sort;
		^myChans
	}

	cleanupElementsAndCollectives {
		responders.do { |resp|
			// resp.postln;
			resp.free;
		};
		midiKeyToElemDict = nil;
	}

	// input
	makeRespFuncs { |srcUid|
		responders = ();
		global = ();

		msgTypes.do { |msgType|
			switch(msgType,
				\cc,          { this.makeChanNumMsgMIDIFunc(msgType, srcUid) },
				\control,     { this.makeChanNumMsgMIDIFunc(msgType, srcUid) },
				\noteOn,      { this.makeChanNumMsgMIDIFunc(msgType, srcUid) },
				\noteOff,     { this.makeChanNumMsgMIDIFunc(msgType, srcUid) },
				\noteOnOff,   {
					this.makeChanNumMsgMIDIFunc(\noteOn, srcUid);
					this.makeChanNumMsgMIDIFunc(\noteOff, srcUid);
				},
				\polyTouch,   { this.makeChanNumMsgMIDIFunc(msgType, srcUid) },
				\polytouch,   { this.makeChanNumMsgMIDIFunc(msgType, srcUid) },

				\bend,        { this.makeChanMsgMIDIFunc   (msgType, srcUid) },
				\touch,       { this.makeChanMsgMIDIFunc   (msgType, srcUid) },
				\program,     { this.makeChanMsgMIDIFunc   (msgType, srcUid) },

				\allNotesOff, { this.makeChanMsgMIDIFunc   (msgType, srcUid) }

				// sysrt and sysex message support here
			);
		};
	}

	// output
	send { |key, val|
		var elem, elemDesc, msgType, chan, num;

		// only called by MKtl when it has a midiout,
		// so we do not check for a midiout here

		elem = mktl.elementsDict[key];
		if (elem.isNil) {
			if (traceRunning) {
				warn("MIDIMKtl send: no elem found for %\n".format(key));
			};
			^this
		};

		elemDesc = elem.elemDesc;

		if (traceRunning) {
			inform("MIDIMKtl will send: " + elem.asCompileString);
			^this
		};

		msgType = elemDesc[\midiMsgType];
		// is this the proper output chan/num?
		// where is it in the elemDesc?
		chan = elemDesc[\midiChan];
		num = elemDesc[\midiNum];

		// could do per-element latency here?
		// e.g. for setting lights 0.1 secs after pressed
		// fork {
		//	(elemDesc[\outLatency] ? 0).wait;
		//  send msg here
		// }

		switch(msgType,
			\cc,  { midiOut.control(chan, num, val ) },
			\control,  { midiOut.control(chan, num, val ) },
			\noteOn, { midiOut.noteOn(chan, num, val ) },
			\noteOff, { midiOut.noteOff(chan, num, val ) },
			\touch, { midiOut.touch(chan, val ) },
			\polyTouch, { midiOut.polyTouch(chan, num, val ) },
			\polytouch, { midiOut.polyTouch(chan, num, val ) },
			\program, { midiOut.program(chan, val ) },
			\bend, { midiOut.bend(chan, val) },

			// tested already?
			\allNotesOff, { midiOut.allNotesOff(chan) },
			\midiClock, { midiOut.midiClock },
			\start, { midiOut.start },
			\stop, { midiOut.stop },
			\continue, { midiOut.continue },
			\reset, { midiOut.reset },

			// working ?
			// these have a really different format
			// \songSelect, { midiOut.songPtr( song ) },
			// \songPtr, { midiOut.songPtr( songPtr ) },
			// \smpte, { midiOut.smpte }

			{
				warn("MIDIMKtlDevice: message type % not recognised"
				.format(msgType))
			}
		)

	}

	// desc file might have a \specialMessages section
	sendSpecialMessage { |name|
		var msg = mktl.desc.specialMessage(name);

		if (msg.notNil and: { midiOut.notNil } ) {
			msg.do { |m| midiOut.performList( m[0], m[1..] ); }
		} {
			"%: could not send specialMessage %.\n".postf(this, name);
		}
	}


	// sendInitialisationMessages {
	// 	mktl.initialisationMessages.do { |it|
	// 		midiOut.performList( it[0], it.copyToEnd(1) );
	// 	}
	// }
}
