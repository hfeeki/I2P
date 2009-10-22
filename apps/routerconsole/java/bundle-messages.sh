#
# Update messages_xx.po and messages_xx.class files,
# from both java and jsp sources.
# Requires installed programs xgettext, msgfmt, msgmerge, and find.
# zzz - public domain
#
CLASS=net.i2p.router.web.messages
TMPFILE=build/javafiles.txt
export TZ=UTC

for i in ../locale/messages_*.po
do
	# get language
	LG=${i#../locale/messages_}
	LG=${LG%.po}

	# make list of java files newer than the .po file
	find src ../jsp/WEB-INF -name *.java -newer $i > $TMPFILE
	if [ -s build/obj/net/i2p/router/web/messages_$LG.class -a ! -s $TMPFILE ]
	then
		continue
	fi

	echo "Generating ${CLASS}_$LG ResourceBundle..."

	# extract strings from java and jsp files, and update messages.po files
	# translate calls must be one of the forms:
	# _("foo")
	# _x("foo")
	# cssHelper._("foo")
	# cssHelper.title("foo")
	# handler._("foo")
	# formhandler._("foo")
	# In a jsp, you must use a helper or handler that has the context set.
	# To start a new translation, copy the header from an old translation to the new .po file,
	# then ant distclean updater.
	find src ../jsp/WEB-INF -name *.java > $TMPFILE
	xgettext -f $TMPFILE -F -L java \
                 --keyword=_ --keyword=_x --keyword=cssHelper._ --keyword=cssHelper.title \
                 --keyword=handler._ --keyword=formhandler._ \
	         -o ${i}t
	if [ $? -ne 0 ]
	then
		echo 'Warning - xgettext failed, not updating translations'
		rm -f ${i}t
		break
	fi
	msgmerge -U --backup=none $i ${i}t
	if [ $? -ne 0 ]
	then
		echo 'Warning - msgmerge failed, not updating translations'
		rm -f ${i}t
		break
	fi
	rm -f ${i}t
	# so we don't do this again
	touch $i

	# convert to class files in build/obj
	msgfmt --java -r $CLASS -l $LG -d build/obj $i
	if [ $? -ne 0 ]
	then
		echo 'Warning - xgettext failed, not updating translations'
		break
	fi
done
rm -f $TMPFILE
# todo: return failure
exit 0
