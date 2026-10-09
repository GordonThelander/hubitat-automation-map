// Creating a room makes two hub calls and no full reload (Gordon, 2026-10-10): the page checks the name against
// the room list it already holds, the app saves the room and confirms that one room, and the page draws it.
String source = new File('apps/automation_map.groovy').getText('UTF-8')
def AppSource = new GroovyClassLoader(this.class.classLoader).parseClass(new File('tests/support/AppSource.groovy'))
String create = AppSource.function(source, 'roomPlanCreateRoom')
assert create.count('hubRoomList()') == 1 : 'the create reads the room list more than once'
assert create.indexOf('roomPlanPostRoom(') < create.indexOf('hubRoomList()') : 'the room list is read before the save again'
assert create.contains('room: created') : 'the create no longer returns the room it confirmed'
int btn = source.indexOf("getElementById('roomPlanNew').addEventListener")
assert source.indexOf('There is already a room called', btn) < source.indexOf('roomPlanCrud({ createRoom', btn) : 'the page no longer checks the name itself'
int crud = source.indexOf('function roomPlanCrud(')
String crudBody = source.substring(crud, source.indexOf('\n}\n', crud))
assert crudBody.indexOf('ROOMPLAN.rooms = (ROOMPLAN.rooms || []).concat([d.room])') < crudBody.indexOf('roomPlanRefreshLive(keep)') : 'a create reloads every device again'
println 'ok   a create is two hub calls, and the room is drawn from the one it confirmed'

// Delete, the same way: the delete and one read confirming the room is gone; the page moves its devices.
String del = AppSource.function(source, 'roomPlanDeleteRoom')
assert !del.contains('roomPlanRoomMembers(') : 'the delete reads every device membership first again'
assert del.count('hubRoomList()') == 1 && del.indexOf('/room/delete/') < del.indexOf('hubRoomList()')
int delBtn = source.indexOf("querySelectorAll('.roomRectDel')")
assert source.indexOf('deleteRoom: { id: btn.getAttribute', delBtn) > 0 : 'the page no longer sends the devices it holds'
assert crudBody.indexOf('body.deleteRoom && d.id') < crudBody.indexOf('roomPlanRefreshLive(keep)') : 'a delete reloads every device again'
println 'ok   a delete is two hub calls, and the page moves the room devices itself'
