/**
 *  VacationLockManager
 *
 *  Copyright 2018 Jonathan Poland
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
definition(
    name: "VacationLockManager",
    namespace: "polandj",
    author: "Jonathan Poland",
    description: "Exposes a web API for calling from zapier to automatically add and remove user lock codes to a zwave/zigbee lock based on the users checkin and checkout dates.",
    category: "Safety & Security",
    iconUrl: "http://cdn.device-icons.smartthings.com/Home/home3-icn.png",
    iconX2Url: "http://cdn.device-icons.smartthings.com/Home/home3-icn@2x.png",
    iconX3Url: "http://cdn.device-icons.smartthings.com/Home/home3-icn@3x.png")

preferences {
    page(name: "pageOne", title: "API and locks", nextPage: "pageTwo", uninstall: true) {
		section("Info") {
    		paragraph title: "API ID", app.getId()
    	}
		section("Lock") {
    		input "lock","capability.lockCodes", title: "Lock", multiple: false
        }
    }
    page(name: "pageTwo", title: "Notifications", nextPage: "pageThree") {
    	section("Phone") {
        	input "ownersms", "phone", title: "Owner SMS Number", required: false
            input "cleanersms", "phone", title: "Cleaners SMS Number", required: false
        }
        section("Special code") {
        	input "notifyuser", "text", title: "Username string to notify on", defaultValue: "cleaners", required: false
        	input "notifyafter", "number", title: "How many hours after using code to notify", defaultValue: 6, range: "0..12", required: true
        	input "notifytext", "text", title: "Text to send in notification", defaultValue: "Cleaners came today, please send payment!", required: true
        }
    }
    page(name: "pageThree", title: "Options", install: true) {
    	section("Check in/out") {
        	input "checkinhour", "number", title: "Check in time (hour of day)", defaultValue: 17, range: "0..23", required: true
        	input "checkouthour", "number", title: "Check out time (hour of day)", defaultValue: 11, range: "0..23", required: true
        }
        section("Code lifetime") {
        	input "hoursbefore", "number", title: "Add code this many hours before checkin", defaultValue: 23, range: "1..48", required: true
        	input "hoursafter", "number", title: "Delete code this many hours after checkout", defaultValue: 6, range: "1..48", required: true
        }
    }
}

mappings {
  path("/reservation") {
    action: [
      POST: "addReservation"
    ]
  }
}

import groovy.json.JsonSlurper

def installed() {
	initialize()
}

def updated() {
	unsubscribe()
	initialize()
}

def initialize() {
	subscribe(lock, "codeReport", codeChange)
    subscribe(lock, "lock", codeUsed)
    runEvery1Hour(checkCodes)
    log.debug "VacationLockManager Initialized with url https://graph-na04-useast2.api.smartthings.com/api/smartapps/installations/${app.getId()}/reservation"
}

/*
 * Called whenever a code is changed in the lock.
 * We use it to confirm our requested changes have been carried out on the lock. 
 */
def codeChange(evt) {
    def slot = "$evt.value" as Integer
    def cachedname
    for (entry in state) {
    	if (entry.value.slot == slot) {
        	cachedname = entry.key
        } else {
        	log.debug "$entry.value.slot != $slot"
        }
    }
    def username = findNameForSlot(slot)
    log.debug "Code change for $slot: $username/$cachedname"
    if (username) {
    	if (username == cachedname) {
        	log.info "User $cachedname addition confirmed"
        	notify(ownersms, "Added code for $username in slot $slot")
        	state[username].confirmed = true
        } else {
        	log.error "Code name mismatch: $username vs $cachedname"
        }
    } else {
    	if (cachedname) {
        	notify(ownersms, "Deleted code for $cachedname in slot $slot")
        	state.remove(cachedname)
            log.info "User $cachedname removed from cache"
        }
    }
   
}

/*
 * Called whenever the lock is locked or unlocked.
 * We use this to optionally monitor for unlocking by a specified user and then notifying.
 */
def codeUsed(evt) {
    if(evt.value == "unlocked" && evt.data) {
        def codeData = new JsonSlurper().parseText(evt.data)
        def username = findNameForSlot(codeData.usedCode)
        log.debug "Unlocked by $username [$codeData.usedCode]"
        if (notifyuser && username.toLowerCase().contains(notifyuser)) {
        	log.debug "Notify soon about unlocked done by $username"
        	runIn(notifyAfter * 3600, notifyCodeUsed)
        }
    }
}

/*
 * Tries to set the code and name on the lock.
 * We confirm the addition in the codeChanged callback.
 */
def addCode(data) {
	def name = data?.name
    def phone = data?.phone
	def code = phone[-4..-1]

	def slot = findSlotNamed(name)
    if (!slot) {
    	slot = findEmptySlot()
    }
    lock.setCode(slot, code, name)
    state[name].slot = slot
	log.debug "Setting code $name = $code in slot $slot"
}

/*
 * Tries to remove the code from the lock.
 * We confirm the addition in the codeChanged callback.
 */
def delCode(data) {
	def name = data?.name

	def slot = findSlotNamed(name)
    if (slot) {
    	lock.deleteCode(slot)
        log.debug "Deleting code for $name in slot $slot"
    } else {
        state.remove(name)
    	notify("Tried to delete code for $name, but it was already deleted")
    	log.info "Tried to delete for $name, but it was already deleted"
    }
}

/* 
 * Get current lock codes from the lock as a map
 */
def getLockCodes() {
	def lockCodes = lock.currentValue("lockCodes")
    def codeData = new JsonSlurper().parseText(lockCodes)
    return codeData
}

/* Given a user, find the slot with that name.
 * Returns 0 on not found, since the slots are 1-indexed (1-30)
 */
def findSlotNamed(user) {
	def lockCodes = getLockCodes()
	def x = lockCodes.find{ it.value == user }?.key
    if (x) {
    	log.debug "User $user is in slot $x"
   	}
    return x as Integer
}

/*
 * Find the user associated with a given slot
 * Returns the name or null if no slot or name
 */
def findNameForSlot(slot) {
	def lockCodes = getLockCodes()
    def x = lockCodes.find{ it.key == slot as String}?.value
    if (x) {
    	log.debug "User $x is in slot $slot"
   	}
    return x
}

/*
 * Finds an empty slot
 * We use this when we're adding a new code to find where to put it.
 * We start at the max code ID (30) and work backwards.
 */
def findEmptySlot() {
	def lockCodes = getLockCodes()
    def maxCodes = lock.currentValue("maxCodes").toInteger()
    def emptySlot = null
    for (def i = maxCodes; i > 0; i--) {
    	if (!lockCodes.get("$i")) {
        	emptySlot = i
            break
        }
    }
    log.debug "Next empty slot is $emptySlot"
    return emptySlot
}

/*
 * Return the number of milliseconds in the given number of hours
 */
def millis(hours) {
	return (hours * 3600000)
}

/*
 * Called every hour, checks that the state of the lock matches our desired state.
 * Sometimes set/delete operations need to be retried on the lock, this does that.
 */
def checkCodes() {
	log.debug "Periodic check of users and codes.."
    def sdf = new java.text.SimpleDateFormat("MMM dd, yyyy")
    sdf.setTimeZone(location.getTimeZone());
    def ltf = new java.text.SimpleDateFormat ("yyyy-MM-dd@HH:mm");
    ltf.setTimeZone(location.getTimeZone());
	Date now = new Date();
    state.each { key, value ->
        def addOnDate = sdf.parse(value.checkin)
        addOnDate.setTime(addOnDate.getTime() + millis(checkinhour) - millis(hoursbefore))
        def delOnDate = sdf.parse(value.checkout)
        delOnDate.setTime(delOnDate.getTime() + millis(checkouthour) + millis(hoursafter))
        if (now < addOnDate) {
        	log.debug "${key}: Early (Now: ${ltf.format(now)} < Add: ${ltf.format(addOnDate)})"
        } else if (now > addOnDate && now < delOnDate) {
        	log.debug "${key}: Active (Add: ${ltf.format(addOnDate)} < Now: ${ltf.format(now)} < Del: ${ltf.format(delOnDate)})"
        	if (!value.confirmed) {
            	// Can't call directly because it manipulates state (which we're iterating)
            	runIn(1, addCode, [data: value])
            }
        } else {
        	log.debug "${key}: Expired (Del: ${ltf.format(delOnDate)} < Now: ${ltf.format(now)})"
            // Can't call directly because it manipulates state (which we're iterating)
            runIn(1, delCode, [data: value])
        }
    }
}

/*
 * The callback for our one API endpoint.  This is called to inform us of a new reservation.
 * Request must specify name, phone, checkin, checkout params
 */
def addReservation() {
	def name = request.JSON?.name
    def phone = request.JSON?.phone
    def checkin = request.JSON?.checkin
    def checkout = request.JSON?.checkout
    def guests = request.JSON?.guests
    
    if (!name || !phone || !checkin || !checkout || !guests) {
    	httpError(400, "Must specify name, phone, checkin, checkout, AND guests parameters")
    }
    
    state[name] = [name: name, phone: phone, 
    			   checkin: checkin, checkout: checkout,
                   slot: 0, confirmed: false]

	log.info "Lock code scheduled for $name, $guests staying $checkin to $checkout"
    notify(cleanersms, "Reminder for ${location.name}: $guests staying $checkin to $checkout")
    checkCodes()
}

/*
 * Called to send notification text after notifyuser unlocked the lock
 */
def notifyCodeUsed() {
	notify(ownersms, notifytext)
}

/*
 * Actually sends the SMS, if a ownersms is configured
 */
def notify(sms, msg) {
    if (sms) {
        sendSms(sms, msg)
    	log.info "Sent SMS '$msg' to $sms"
    }
}