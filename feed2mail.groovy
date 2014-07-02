@Grab(group='rome', module='rome', version='1.0')
@Grab(group='javax.mail', module='mail', version='1.4.7')
@Grab(group='com.gmongo', module='gmongo', version='1.2')
@Grab(group='net.java.dev', module='jvyaml', version='0.2.1')
@Grab(group='org.jyaml', module='jyaml', version='1.3')

import com.sun.syndication.feed.synd.*
import com.sun.syndication.io.*

import java.util.*
import javax.mail.*
import javax.mail.internet.*
import javax.activation.*

import com.mongodb.*
import com.gmongo.*

import org.ho.yaml.Yaml

def set_mailinfo(Properties props, settings) {
	props.put('mail.debug', settings.smtp.debug)
	props.put('mail.smtp.host', settings.smtp.host)
	props.put('mail.smtp.auth', settings.smtp.auth)
	Session session = Session.getDefaultInstance(props)

	Message msg = new MimeMessage(session)
	msg.setFrom(new InternetAddress(settings.smtp.from))
	msg.setSender(new InternetAddress(settings.smtp.sender))
	//msg.setRecipient(Message.RecipientType.TO, new InternetAddress(settings.smtp.recipient))
	msg.setRecipients(Message.RecipientType.TO, settings.smtp.recipient)

	return msg
}

def sendmail(msg) {
	msg.setHeader('Content-Transfer-Encoding', 'base64')

	Transport.send(msg)
}

def set_feedinfo(uri) {
	SyndFeed feed = new SyndFeedInput(false).build(
		new InputStreamReader(new URL(uri).openStream(), "UTF-8")
	)

	return feed
}

def init_db(settings) {
	mongo = new GMongo(settings.mongo.host, 27017)
	db = mongo.getDB(settings.mongo.db)
	col = db[settings.mongo.collection]
	col.ensureIndex(
		new BasicDBObject([feedTitle:true, link:true]),
		new BasicDBObject([unique:true])
	)
	col.setWriteConcern(WriteConcern.NORMAL)

	return col
}

def send_feed(feed, col, msg) {
	for( entry in feed.entries ) {
		if (col.find([link: entry.link]).count() == 0) {
			println "contents.size = $entry.contents.size"
			String strbuf = ""
			entry.contents.eachWithIndex { value, i ->
				//println "contents[${i}]"
				strbuf <<= value.value
			}
			strbuf = strbuf.trim()
			if (entry.contents.size && strbuf) {
				write = col.insert([feedTitle:feed.title,
						link:entry.link,
						updatedDate:entry.updatedDate])
				if (!write.getError()) { println "error:" + write.getError()}
	
				msg.setSubject('[feed2mail:new] ' + entry.title, 'utf-8')
				updatedDate = entry.updatedDate.toString()
				feedTitle = feed.title.toString()
				entryTitle = entry.title.toString()
				link = entry.link.toString()
				strbuf = "$updatedDate<br />$feedTitle<br />$entryTitle<br />$link<br /><hr />$strbuf".toString()
				msg.setContent(strbuf, 'text/html; charset=utf-8')
				sendmail(msg)
				continue
			}
		}
	
		col.find([link: entry.link, updatedDate: [$lt: entry.updatedDate]]).each {
			//TODO: リファクタリング
			//println it
			msg.setSubject('[feed2mail:update] ' + entry.title, 'utf-8')
			updatedDate = entry.updatedDate.toString()
			feedTitle = feed.title.toString()
			entryTitle = entry.title.toString()
			link = entry.link.toString()
			strbuf = "$updatedDate<br />$feedTitle<br />$entryTitle<br />$link<br /><hr />$strbuf".toString()
			msg.setContent(strbuf, 'text/html; charset=utf-8')
			sendmail(msg)
		}
	}
}

def settings = Yaml.load(new File('feed2mail.yml').text)

Properties props = new Properties()
Message msg = set_mailinfo(props, settings)

col = init_db(settings)

settings.feed.uri.each {
	send_feed(set_feedinfo(it), col, msg)
}
