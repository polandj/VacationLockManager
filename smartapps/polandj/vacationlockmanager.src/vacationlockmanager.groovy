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
	section("Info") {
    	paragraph title: "API ID",app.getId()
    }
	section("Settings") {
    	input "lock","capability.lockCodes", title: "Locks", multiple: true
        input "phone", "phone", title: "Send a Text Message?", required: false
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
	subscribe(lock, "codeReport", codereturn)
    subscribe(lock, "lock", codeUsed)
}

def codereturn(evt) {
    log.debug "codereturn"
    def username = "$evt.value"
    def code = "$evt.data"
    //def code = evt.data.replaceAll("\\D+","")
    if (code == "") {
        def message = "User in slot $evt.value was deleted from $evt.displayName"
        log.debug message
    } else {
        def message = "Code for user $username in user slot $evt.value was set to $code on $evt.displayName"
        log.debug message
    }
}

def codeUsed(evt) {
    if(evt.value == "unlocked" && evt.data) {
        def username
        def sendCode
        def codeData = new JsonSlurper().parseText(evt.data)
        settings.each {
            if( it.key == "username${codeData.usedCode}" ) {
                username = "$it.value"
            }
            if ( it.key == "sendCode${codeData.usedCode}" ) {
                sendCode = "$it.value"
            }
        }
        if (username.toLowerCase().contains("cleaners")) {
        	runIn(7 * 3600, cleanersCame)
        }
    }
}

private addCode(data) {
	def name = data?.name
    def phone = data?.phone
    def code = phone[-4..-1]

	def slot = findSlotNamed(name)
    if (!slot) {
    	slot = findEmptySlot()
    }
    lock.setCode(slot, code, name)
    notify("Set code '$code' for $name in slot $slot")
	log.debug "Set code $name = $code in slot $slot"
}

private delCode(data) {
	def name = data?.name
    def phone = data?.phone
    def code = phone[-4..-1]

	def slot = findSlotNamed(name)
    if (slot) {
    	lock.deleteCode(slot)
    	notify("Deleted code for $name in slot $slot")
    } else {
    	notify("Tried to delete code for $name, but it was already deleted")
    	log.info "Tried to delete for $name, but it was already deleted"
    }
}

private findSlotNamed(user) {
	def lockCodes = lock.currentValue("lockCodes").first()
    def codeData = new JsonSlurper().parseText(lockCodes)
	def x = codeData.find{ it.value == user }?.key
    if (x) {
    	log.debug "User $user is in slot $x"
   	}
    return x
}

private findEmptySlot() {
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
    

def addReservation() {
	Date now = new Date();
	def sdf = new java.text.SimpleDateFormat("MMM dd, yyyy")
    sdf.setTimeZone(TimeZone.getTimeZone("EST"));
    
    def addOnDate = sdf.parse(request.JSON?.checkin)
    addOnDate.setTime(addOnDate.getTime() - (15 * 3600000))
    if (addOnDate > now) {
    	runOnce(addOnDate, addCode, [data:request.JSON])
    } else {
    	log.error "Check-in is in the past! $addOnDate < $now"
    }
    
    def delOnDate = sdf.parse(request.JSON?.checkout)
    delOnDate.setTime(delOnDate.getTime() + (18 * 3600000))
    if (delOnDate > now) {
        runOnce(delOnDate, delCode, [data:request.JSON])
    } else {
    	log.error "Check-out is in the past! $delOnDate < $now"
    }
    
    def name = request.JSON?.name
    def checkin = request.JSON?.checkin
    def checkout = request.JSON?.checkout

	log.debug "Posted a new reservation"
    notify("Lock code scheduled for $name, staying $checkin to $checkout")
}

private cleanersCame() {
	notify("The cleaners came a little while ago, pay them")
}

private notify(msg) {
    if (phone) {
        sendSms(phone, msg)
    	log.info "Sent SMS '$msg' to $phone"
    }
}