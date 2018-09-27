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
    description: "Exposes a web API for calling from zapier to automatically add and remove user lock codes to a zwave lock based on the users checkin and checkout dates.",
    category: "Safety & Security",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png")

preferences {
    page(name: "pageOne", title: "API and locks", nextPage: "pageTwo", uninstall: true) {
		section("Info") {
    		paragraph title: "API ID", app.getId()
    	}
		section("Locks") {
    		input "lock","capability.lockCodes", title: "Locks", multiple: true
        }
    }
    page(name: "pageTwo", title: "Notifications", nextPage: "pageThree") {
    	section("Phone") {
        	input "phone", "phone", title: "Send Text Messages To", required: false
        }
        section("Special code") {
        	input "notifycode", "text", title: "Code to notify on", required: false
        	input "notifyafter", "number", title: "How many hours after using code to notify", defaultValue: 6, range: "0..12", required: true
        	input "notifytext", "text", title: "Text to send in notification", defaultValue: "Cleaners came, pay them", required: true
        }
    }
    page(name: "pageThree", title: "Options", install: true) {
    	section("Check in/out") {
        	input "checkinhour", "number", title: "Check in time (hour of day)", defaultValue: 17, range: "0..23", required: true
        	input "checkouthour", "number", title: "Check out time (hour of day)", defaultValue: 11, range: "0..23", required: true
        }
        section("Code lifetime") {
        	input "hoursbefore", "number", title: "Add code this many hours before checkin", defaultValue:8, range: "1..48", required: true
        	input "hoursafter", "number", title: "Delete code this many hours after checkout", defaultValue: 5, range: "1..48", required: true
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
	log.debug "VacationLockManager Initialized with url https://graph-na04-useast2.api.smartthings.com/api/smartapps/installations/${app.getId()}/reservation"
	subscribe(lock, "codeReport", codeChange)
    subscribe(lock, "lock", codeUsed)
    runEvery1Hour(checkCodes)
}

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
        	notify("Added code for $username in slot $slot")
        	state[username].confirmed = true
        } else {
        	log.error "Code name mismatch: $username vs $cachedname"
        }
    } else {
    	if (cachedname) {
        	notify("Deleted code for $cachedname in slot $slot")
        	state.remove(cachedname)
            log.info "User $cachedname removed from cache"
        }
    }
   
}

def codeUsed(evt) {
    if(evt.value == "unlocked" && evt.data) {
        def codeData = new JsonSlurper().parseText(evt.data)
        if (notifycode && notifyCode == codeData.usedCode) {
        	runIn(notifyAfter * 3600, notifyCodeUsed)
        }
    }
}

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

def findSlotNamed(user) {
	def lockCodes = lock.currentValue("lockCodes").first()
    def codeData = new JsonSlurper().parseText(lockCodes)
	def x = codeData.find{ it.value == user }?.key
    if (x) {
    	log.debug "User $user is in slot $x"
   	}
    return x
}

def findNameForSlot(slot) {
	def lockCodes = lock.currentValue("lockCodes").first()
    def codeData = new JsonSlurper().parseText(lockCodes)
	def x = codeData.find{ it.key == slot }?.value
    if (x) {
    	log.debug "User $x is in slot $slot"
   	}
    return x
}

def findEmptySlot() {
	def lockCodes = lock.currentValue("lockCodes").first()
    def codeData = new JsonSlurper().parseText(lockCodes)
    def maxCodes = lock.currentValue("maxCodes").first().toInteger()
    def emptySlot = null
    for (def i = 1; i <= maxCodes; i++) {
    	if (!codeData.get("$i")) {
        	emptySlot = i
            break
        }
    }
    log.debug "Next empty slot is $emptySlot"
    return emptySlot
}

def checkCodes() {
	log.debug "Checking codes"
    def sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")
    sdf.setTimeZone(location.getTimeZone());
	Date now = new Date();
	for ( entry in state ) {
    	log.debug "Check ${entry.key} -> ${entry.value}"
        def addOnDate = sdf.parse(entry.value.addondate)
        def delOnDate = sdf.parse(entry.value.delondate)
        if (now < addOnDate) {
        	// DO nothing
        } else if (now > addOnDate && now < delOnDate) {
        	if (!entry.value.confirmed) {
            	addCode(entry.value)
            }
        } else {
        	delCode(entry.value)
        }
    }
}

def addReservation() {
	Date now = new Date();
	def sdf = new java.text.SimpleDateFormat("MMM dd, yyyy")
    sdf.setTimeZone(location.getTimeZone());
    
    def addOnDate = sdf.parse(request.JSON?.checkin)
    addOnDate.setTime(addOnDate.getTime() + (checkinhour * 3600000) - (hoursbefore * 3600000))
    if (addOnDate > now) {
    	runOnce(addOnDate, addCode, [data:request.JSON])
    }
    
    def delOnDate = sdf.parse(request.JSON?.checkout)
    delOnDate.setTime(delOnDate.getTime() + (checkouthour * 3600000) + (hoursafter * 3600000))
    if (delOnDate > now) {
        runOnce(delOnDate, delCode, [data:request.JSON])
    }

    def name = request.JSON?.name
    def phone = request.JSON?.phone
    def checkin = request.JSON?.checkin
    def checkout = request.JSON?.checkout
    
    state[name] = [name: name, phone: phone, 
    			   checkin: checkin, checkout: checkout, 
                   addondate: addOnDate, delondate: delOnDate, 
                   slot: 0, confirmed: false]

	def df = new java.text.SimpleDateFormat ("yyyy-MM-dd@HH:mm");
    df.setTimeZone(location.getTimeZone());
	log.debug "Posted a new reservation.  ${df.format(addOnDate)} -> ${df.format(delOnDate)}. $state"
    notify("Lock code scheduled for $name, staying $checkin to $checkout")
}

def notifyCodeUsed() {
	notify(notifytext)
}

def notify(msg) {
    if (phone) {
        sendSms(phone, msg)
    	log.info "Sent SMS '$msg' to $phone"
    }
}