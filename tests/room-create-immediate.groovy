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
// Claude HAM's 2.4.20 check: a create that landed reported 'Failed: TypeError' because the request's catch also
// wrapped the redraw. The catch must come before the success handling, and the redraw must catch its own errors.
assert crudBody.indexOf(".catch(function (e) { msg.textContent = 'Failed: ' + e; return null; })") < crudBody.indexOf('if (!(d && d.ok))') : 'a redraw error can read as a failed change again'
assert !crudBody.contains('roomPlanRender();') : 'roomPlanCrud redraws without the guard'
String redraw = source.substring(source.indexOf('function roomPlanRedrawAfter('), source.indexOf('function roomPlanCrud('))
assert redraw.contains('if (!ICONS) { roomPlanLoad(); return; }') && redraw.contains('catch (e)')
println 'ok   a create is two hub calls, and the room is drawn from the one it confirmed'

// Delete, the same way: the delete and one read confirming the room is gone; the page moves its devices.
String del = AppSource.function(source, 'roomPlanDeleteRoom')
assert !del.contains('roomPlanRoomMembers(') : 'the delete reads every device membership first again'
assert del.count('hubRoomList()') == 1 && del.indexOf('/room/delete/') < del.indexOf('hubRoomList()')
int delBtn = source.indexOf("querySelectorAll('.roomRectDel')")
assert source.indexOf('deleteRoom: { id: delId, deviceIds: devIds }', delBtn) > 0 : 'the page no longer sends the devices it holds'
assert crudBody.indexOf('body.deleteRoom && d.id') < crudBody.indexOf('roomPlanRefreshLive(keep)') : 'a delete reloads every device again'
println 'ok   a delete is two hub calls, and the page moves the room devices itself'

// Option A (Gordon, 2026-10-10): the hub's own save takes about 11 s, so a create or delete is drawn at once as
// pending, confirmed or undone when the hub answers, and a pending room takes no drops.
int newBtn = source.indexOf("getElementById('roomPlanNew').addEventListener")
assert source.indexOf("pending: 'create'", newBtn) < source.indexOf('roomPlanCrud({ createRoom', newBtn) : 'a create is not drawn before the hub answers'
assert source.indexOf("r.pending = 'delete'", delBtn) < source.indexOf('roomPlanCrud({ deleteRoom', delBtn) : 'a delete is not shown before the hub answers'
assert crudBody.count('roomPlanUndoPending(body)') == 2 : 'a refused or failed change leaves its pending room behind'
assert source.contains("(pending ? '' : ' data-drop=")  : 'a pending room accepts drops'
assert source.contains("'Saving to hub...'") && source.contains(".roomRect.rpPending")
println 'ok   a create or delete shows at once as pending, and is confirmed or undone by the hub answer'
