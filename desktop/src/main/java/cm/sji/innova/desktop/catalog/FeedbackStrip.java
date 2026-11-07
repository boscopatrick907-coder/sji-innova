package cm.sji.innova.desktop.catalog;

import java.awt.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.JTextComponent;

/** Shared, labelled feedback. Blank bound messages take no layout space. Access on the EDT. */
public final class FeedbackStrip extends JPanel {
    public enum Tone { INFO, SUCCESS, WARNING, ERROR }
    private final JLabel icon=CatalogStyle.label("",17,true);
    private final JComponent message;
    public FeedbackStrip(JComponent message,Tone tone,boolean bindVisibility){
        super(new BorderLayout(9,0));this.message=message;
        setOpaque(true);setBorder(BorderFactory.createEmptyBorder(10,12,10,12));
        add(icon,BorderLayout.WEST);add(message,BorderLayout.CENTER);setTone(tone);
        if(bindVisibility){
            JComponent text=textComponent();
            if(text instanceof JLabel label)label.addPropertyChangeListener("text",e->updateVisibility());
            else if(text instanceof JTextComponent area)area.getDocument().addDocumentListener(new DocumentListener(){
                public void insertUpdate(DocumentEvent e){updateVisibility();}public void removeUpdate(DocumentEvent e){updateVisibility();}public void changedUpdate(DocumentEvent e){updateVisibility();}
            });
            updateVisibility();
        }
    }
    private JComponent textComponent(){return message instanceof JScrollPane scroll&&scroll.getViewport().getView() instanceof JComponent view?view:message;}
    private void updateVisibility(){
        JComponent text=textComponent();String value=text instanceof JLabel label?label.getText():text instanceof JTextComponent area?area.getText():"message";
        setVisible(value!=null&&!value.isBlank());revalidate();if(getParent()!=null)getParent().revalidate();
    }
    public void setTone(Tone tone){
        Color background=switch(tone){case INFO->new Color(0xEDF2E9);case SUCCESS->new Color(0xE4F0E8);case WARNING->new Color(0xFFF2DD);case ERROR->new Color(0xFDECEA);};
        Color foreground=switch(tone){case INFO->CatalogStyle.MUTED;case SUCCESS->CatalogStyle.GREEN;case WARNING->new Color(0x8A571E);case ERROR->new Color(0xA52D26);};
        setBackground(background);icon.setForeground(foreground);icon.setText(switch(tone){case INFO->"i";case SUCCESS->"✓";case WARNING,ERROR->"!";});
        textComponent().setForeground(foreground);repaint();
    }
    @Override protected void paintComponent(Graphics graphics){
        if(!isOpaque())return;
        Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(getBackground());g.fillRoundRect(0,0,getWidth(),getHeight(),12,12);g.dispose();
    }
}
